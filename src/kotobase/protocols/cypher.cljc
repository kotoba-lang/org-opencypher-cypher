(ns kotobase.protocols.cypher
  "cypher.kotobase.net -- an openCypher (openCypher.org) query surface over
  kotobase, via the shared `kotobase.query.bridge` (ADR-2607172300 in
  `com-junkawasaki/root`).

  **Why HTTP, not Bolt**: Bolt is binary, stateful, and has no precedent in
  this workspace; the HTTP transaction-endpoint shape (Neo4j's HTTP Cypher
  API) fits the established `.cljc` ring-shaped handler pattern
  (`kotobase.protocols.http`) directly, and is the ADR's explicit,
  deliberate v0.1 decision -- not revisited here.

  ## Wire shape: `POST /db/data/transaction/commit`

  Request body (JSON):
  ```json
  {\"statements\": [{\"statement\": \"MATCH (n:users) WHERE n.role = 'admin' RETURN n.name, n.role\",
                    \"parameters\": {}}]}
  ```
  Response body (JSON), always HTTP 200 once the request itself parses as
  valid JSON with a `statements` array (matches real Neo4j: query-level
  errors are reported IN the body, not via HTTP status):
  ```json
  {\"results\": [{\"columns\": [\"n.name\", \"n.role\"],
                 \"data\": [{\"row\": [\"Alice\", \"admin\"], \"meta\": [null, null]}]}],
   \"errors\": []}
  ```
  Statements run in order; the first statement whose Cypher fails to parse
  or execute aborts the whole request -- `results` holds every statement
  that ran successfully before it, `errors` holds exactly one entry
  `{\"code\" \"...\" \"message\" \"...\"}`, and statements after the failing one
  do not run. This mirrors the real HTTP Cypher API's auto-commit,
  abort-on-first-error transaction semantics. `\"code\"` values are modeled on
  (not a guaranteed-identical enumeration of) Neo4j's
  `Neo.ClientError.Statement.*` / `Neo.ClientError.Request.*` naming.

  Malformed JSON, or JSON without a `statements` array, is a request-level
  failure -- HTTP 400, `{\"results\" [] \"errors\" [...]}` -- since there is no
  statement to even attempt.

  ## v0.1 Cypher subset (hard scope boundary, ADR-2607172300)

  ```
  MATCH (n:Label) [WHERE n.prop = <value> [AND n.prop2 = <value2> ...]]
    RETURN n.prop1 [, n.prop2 ...]
  ```

  - exactly one `MATCH` clause, one `RETURN` clause, at most one `WHERE`.
  - every node pattern MUST be labeled (`(n:Label)`, not bare `(n)`).
  - `WHERE` is equality-only (`=`), one or more clauses ANDed together --
    no `<`/`>`/`<>`/`OR`/`NOT`/`IS NULL`/string functions.
  - `RETURN` projects one or more `var.prop` properties -- never a bare
    node/relationship, never `*`, never an alias (`AS`), never an
    aggregate/`ORDER BY`/`LIMIT`/`SKIP`/`DISTINCT`.
  - values are string/number/boolean/`null`/`$parameter` literals only.
  - **no** `CREATE`/`MERGE`/`DELETE`/`SET`/`REMOVE`/`DETACH`/`FOREACH`/
    `CALL`/`UNWIND`/`LOAD CSV` -- this is a READ-ONLY query surface,
    rejected with an explicit, named error (not silently mis-executed).
  - **no** variable-length paths (`-[:REL*1..3]->`), no multi-hop chains
    (more than one relationship in a single pattern).
  - each of these is rejected with a clear parse-time error, never
    silently ignored or partially executed -- see `parse`.

  Row order is NOT part of the v0.1 contract (no `ORDER BY`): `execute`
  sorts rows by their stringified value for deterministic output across
  runs, which is an implementation convenience, not a Cypher `ORDER BY`.

  ## Label -> collection mapping

  A Cypher label is used VERBATIM as the `kotobase.store` collection name
  materialized via `kotobase.query.bridge/materialize` -- `(n:users)`
  queries the `\"users\"` collection exactly. No case-folding, no
  pluralization, no other transform. Document collections accordingly.

  ## Bonus: relationship patterns (real cross-collection join, not required
  by v0.1 but implemented since it maps naturally onto the bridge's join
  capability)

  `(a:LabelA)-[:REL_TYPE]->(b:LabelB)` (single hop, directed rightward
  only -- no `<-`, no undirected `--`) translates to a real
  `kotobase.query.bridge` cross-collection join: it requires `a`'s
  materialized document to carry an attribute named
  `(keyword (clojure.string/lower-case \"REL_TYPE\"))` (i.e. the
  relationship type, case-folded to lower, underscores UNCHANGED -- e.g.
  `WORKS_AT` -> `:works_at`) whose value equals `b`'s `:kotobase/key`
  (materialized as a STRING, per `kotobase.query.bridge`'s own doc->datoms
  mapping) -- exactly the foreign-key-attribute join convention
  `kotobase-query`'s own README worked example uses, just with the
  attribute name derived mechanically from the relationship type instead
  of chosen ad hoc. This is this repo's own documented convention (there is
  no such thing as a stored 'relationship' or 'edge' in kotobase's flat
  document model) -- see `test/kotobase/protocols/cypher_test.cljc` for a
  worked fixture.

  ## `visible?` -- required, injectable, never defaulted

  `ctx` passed to `handle` MUST include `:visible?`, a predicate over
  materialized datoms (`(fn [{:keys [s p o]}] boolean?)`) -- the same
  discipline `kotobase.query.bridge`/`arrangement.datalog` enforce
  (ADR-2607050500, \"Query as first-class effect\"). `handle` throws
  immediately (a ctx-construction bug, not a wire-protocol error) if
  `:visible?` is missing -- there is no silent `(constantly true)` fallback
  anywhere in this namespace.

  ## Audit choice

  This is a read-only surface, so there is nothing to audit in the
  write-audit sense `kotobase.protocols.s3`/`ipfs-pinning` use (no mutated
  doc to log). Per ADR-2607050500's \"query as first-class effect\"
  framing, every processed statement is still appended to the shared
  `:kotobase.protocols/audit` stream regardless of outcome: `{:surface
  :cypher :op :query :statement <raw text> :row-count N}` on success,
  `{:surface :cypher :op :query-error :statement <raw text> :code ...
  :error ...}` on failure. Parameter VALUES are never audited (only the
  raw statement text, which is the caller's own Cypher, and a row count) --
  avoids leaking materialized property values or parameter payloads into
  the audit trail."
  (:require [clojure.string :as str]
            [kotobase.protocols.http :as http]
            [kotobase.protocols.json :as json]
            [kotobase.query.bridge :as bridge]
            [kotobase.store :as st]))

;; --------------------------------------------------------------- errors

(defn- syntax-err [msg]
  (ex-info msg {:cypher/code "Neo.ClientError.Statement.SyntaxError"
                :cypher/message msg}))

(defn- semantic-err [msg]
  (ex-info msg {:cypher/code "Neo.ClientError.Statement.SemanticError"
                :cypher/message msg}))

(defn- param-err [msg]
  (ex-info msg {:cypher/code "Neo.ClientError.Request.InvalidFormat"
                :cypher/message msg}))

;; ------------------------------------------------------------- tokenize

(defn- tokenize-string
  "`s`, index `i` at the opening quote char `quote` -> `[unescaped-value
  index-after-closing-quote]`."
  [s i quote]
  (loop [i (inc i) acc ""]
    (if (>= i (count s))
      (throw (syntax-err "unterminated string literal"))
      (let [c (subs s i (inc i))]
        (cond
          (= c quote) [acc (inc i)]
          (= c "\\")
          (let [e (subs s (inc i) (+ i 2))]
            (cond
              (= e "\\") (recur (+ i 2) (str acc "\\"))
              (= e "n") (recur (+ i 2) (str acc "\n"))
              (= e "t") (recur (+ i 2) (str acc "\t"))
              (= e quote) (recur (+ i 2) (str acc quote))
              :else (recur (+ i 2) (str acc e))))
          :else (recur (inc i) (str acc c)))))))

(def ^:private keyword-tokens
  {"MATCH" :match "WHERE" :where "RETURN" :return "AND" :and
   "TRUE" :true "FALSE" :false "NULL" :null})

(defn- tokenize
  "Cypher statement string -> vector of token maps `{:type kw :val v}`.
  Hand-rolled (no regex-based/3rd-party Cypher parser -- v0.1's grammar is
  small enough that a scanner + recursive-descent parser over these tokens
  is the right size of tool)."
  [s]
  (let [n (count s)]
    (loop [i 0 toks []]
      (if (>= i n)
        toks
        (let [c (subs s i (inc i))]
          (cond
            (re-matches #"[ \t\n\r]" c) (recur (inc i) toks)
            (= c "(") (recur (inc i) (conj toks {:type :lparen}))
            (= c ")") (recur (inc i) (conj toks {:type :rparen}))
            (= c "[") (recur (inc i) (conj toks {:type :lbracket}))
            (= c "]") (recur (inc i) (conj toks {:type :rbracket}))
            (= c ":") (recur (inc i) (conj toks {:type :colon}))
            (= c ".") (recur (inc i) (conj toks {:type :dot}))
            (= c ",") (recur (inc i) (conj toks {:type :comma}))
            (= c "=") (recur (inc i) (conj toks {:type :eq}))
            (= c "*") (recur (inc i) (conj toks {:type :star}))
            (= c "-")
            (if (= (subs s (inc i) (min n (+ i 2))) ">")
              (recur (+ i 2) (conj toks {:type :arrow}))
              (recur (inc i) (conj toks {:type :dash})))
            (= c "$")
            (let [m (re-find #"^[A-Za-z_][A-Za-z0-9_]*" (subs s (inc i)))]
              (if m
                (recur (+ i 1 (count m)) (conj toks {:type :param :val m}))
                (throw (syntax-err (str "expected an identifier after '$' at position " i)))))
            (or (= c "'") (= c "\""))
            (let [[v i'] (tokenize-string s i c)]
              (recur i' (conj toks {:type :string :val v})))
            (re-matches #"[0-9]" c)
            (let [m (re-find #"^[0-9]+(?:\.[0-9]+)?" (subs s i))
                  numv (if (str/includes? m ".")
                         #?(:clj (Double/parseDouble m) :cljs (js/parseFloat m))
                         #?(:clj (Long/parseLong m) :cljs (js/parseInt m 10)))]
              (recur (+ i (count m)) (conj toks {:type :number :val numv})))
            (re-matches #"[A-Za-z_]" c)
            (let [m (re-find #"^[A-Za-z_][A-Za-z0-9_]*" (subs s i))]
              (recur (+ i (count m))
                     (conj toks (if-let [kwtype (get keyword-tokens (str/upper-case m))]
                                  {:type kwtype}
                                  {:type :ident :val m}))))
            :else
            (throw (syntax-err (str "unexpected character '" c "' at position " i)))))))))

;; ---------------------------------------------------------------- parse

(defn- peek-type [toks] (:type (first toks)))

(defn- describe-tok [t]
  (case (:type t)
    :ident (str "identifier '" (:val t) "'")
    :string "string literal"
    :number (str "number " (:val t))
    :param (str "parameter '$" (:val t) "'")
    nil "end of input"
    (name (:type t))))

(defn- expect [toks type]
  (if (= type (peek-type toks))
    (rest toks)
    (throw (syntax-err (str "expected " (name type) ", got " (describe-tok (first toks)))))))

(defn- take-ident [toks]
  (if (= :ident (peek-type toks))
    [(:val (first toks)) (rest toks)]
    (throw (syntax-err (str "expected an identifier, got " (describe-tok (first toks)))))))

(def ^:private unsupported-clause-keywords
  #{"CREATE" "MERGE" "DELETE" "SET" "REMOVE" "DETACH" "FOREACH" "CALL" "UNWIND" "LOAD"})

(defn- expect-match [toks]
  (cond
    (= :match (peek-type toks)) (rest toks)
    (and (= :ident (peek-type toks))
         (contains? unsupported-clause-keywords (str/upper-case (:val (first toks)))))
    (throw (syntax-err (str (str/upper-case (:val (first toks)))
                            " is not supported -- org-opencypher-cypher v0.1 is a"
                            " READ-ONLY query surface (MATCH/WHERE/RETURN only;"
                            " ADR-2607172300)")))
    :else
    (throw (syntax-err (str "expected MATCH, got " (describe-tok (first toks)))))))

(defn- parse-node [toks]
  (let [toks (expect toks :lparen)
        [v toks] (take-ident toks)
        [label toks] (if (= :colon (peek-type toks))
                       (let [toks (rest toks)]
                         (take-ident toks))
                       (throw (syntax-err
                               (str "node pattern '(" v ")' has no label -- v0.1 requires"
                                    " every node pattern to be labeled, e.g. (" v ":users)"))))
        toks (expect toks :rparen)]
    [{:var v :label label} toks]))

(defn- parse-rel [toks]
  (let [toks (expect toks :dash)
        toks (expect toks :lbracket)
        toks (expect toks :colon)
        [reltype toks] (take-ident toks)
        _ (when (= :star (peek-type toks))
            (throw (syntax-err
                    "variable-length relationship patterns ([:REL*..]) are not supported in v0.1")))
        toks (expect toks :rbracket)
        toks (if (= :arrow (peek-type toks))
               (rest toks)
               (throw (syntax-err
                       (str "only directed rightward relationship patterns -[:REL]-> are"
                            " supported in v0.1 (no <-, no undirected --); got "
                            (describe-tok (first toks))))))]
    [reltype toks]))

(defn- parse-pattern [toks]
  (let [[n1 toks] (parse-node toks)]
    (if (= :dash (peek-type toks))
      (let [[reltype toks] (parse-rel toks)
            [n2 toks] (parse-node toks)]
        (if (= :dash (peek-type toks))
          (throw (syntax-err
                  "path patterns with more than one relationship (multi-hop) are not supported in v0.1"))
          [{:nodes [n1 n2] :rel-type reltype} toks]))
      [{:nodes [n1] :rel-type nil} toks])))

(defn- parse-value [toks]
  (let [t (first toks)]
    (case (:type t)
      :string [(:val t) (rest toks)]
      :number [(:val t) (rest toks)]
      :true [true (rest toks)]
      :false [false (rest toks)]
      :null [nil (rest toks)]
      :param [{:cypher/param (:val t)} (rest toks)]
      :dash (let [t2 (second toks)]
              (if (= :number (:type t2))
                [(- (:val t2)) (drop 2 toks)]
                (throw (syntax-err "expected a number after '-'"))))
      (throw (syntax-err
              (str "expected a value (string, number, true/false, null, or $parameter), got "
                   (describe-tok t)))))))

(defn- parse-predicate [toks]
  (let [[v toks] (take-ident toks)
        toks (expect toks :dot)
        [prop toks] (take-ident toks)
        toks (expect toks :eq)
        [val toks] (parse-value toks)]
    [{:var v :prop prop :value val} toks]))

(defn- parse-where [toks]
  (if (= :where (peek-type toks))
    (loop [toks (rest toks) preds []]
      (let [[p toks] (parse-predicate toks)
            preds (conj preds p)]
        (if (= :and (peek-type toks))
          (recur (rest toks) preds)
          [preds toks])))
    [[] toks]))

(defn- parse-return-item [toks]
  (let [[v toks] (take-ident toks)
        toks (expect toks :dot)
        [prop toks] (take-ident toks)]
    [{:var v :prop prop} toks]))

(defn- parse-return [toks]
  (let [toks (expect toks :return)]
    (when-not (= :ident (peek-type toks))
      (throw (syntax-err "RETURN must project at least one property (var.prop)")))
    (loop [toks toks items []]
      (let [[item toks] (parse-return-item toks)
            items (conj items item)]
        (if (= :comma (peek-type toks))
          (recur (rest toks) items)
          [items toks])))))

(defn parse
  "Parse one Cypher v0.1 statement string into an AST map:
  `{:pattern {:nodes [{:var :label} ...] :rel-type string-or-nil}
    :where [{:var :prop :value} ...]
    :return [{:var :prop} ...]}`.

  Throws `ex-info` (`(:cypher/code (ex-data e))` is a
  `\"Neo.ClientError.Statement.*\"` string, `(:cypher/message (ex-data e))`
  a human-readable message) for anything outside the v0.1 subset -- see the
  ns docstring's grammar section. Never silently accepts or partially
  executes out-of-scope syntax."
  [s]
  (let [toks (tokenize s)
        toks (expect-match toks)
        [pattern toks] (parse-pattern toks)
        [wpreds toks] (parse-where toks)
        [ritems toks] (parse-return toks)]
    (when (seq toks)
      (throw (syntax-err (str "unexpected trailing input starting at " (describe-tok (first toks))))))
    (when (empty? ritems)
      (throw (syntax-err "RETURN must project at least one property (var.prop)")))
    (let [declared (into #{} (map :var (:nodes pattern)))
          check-var (fn [v where-desc]
                      (when-not (contains? declared v)
                        (throw (semantic-err
                                (str "unknown variable '" v "' in " where-desc
                                     " -- not declared in the MATCH pattern")))))]
      (doseq [p wpreds] (check-var (:var p) "WHERE"))
      (doseq [r ritems] (check-var (:var r) "RETURN")))
    {:pattern pattern :where wpreds :return ritems}))

;; ------------------------------------------------------------ translate

(defn- var-sym [v] (symbol (str "?" v)))

(defn- fk-attr
  "Relationship-type -> foreign-key attribute convention for the
  (bonus, non-required) relationship-pattern join -- see ns docstring."
  [rel-type]
  (keyword (str/lower-case rel-type)))

(defn- resolve-value [v parameters]
  (if (and (map? v) (contains? v :cypher/param))
    (let [pname (:cypher/param v)]
      (if (contains? parameters pname)
        (get parameters pname)
        (throw (param-err (str "missing parameter: $" pname)))))
    v))

(defn translate
  "AST (from `parse`) + `parameters` (a string-keyed map -- the JSON-parsed
  `\"parameters\"` object from the request statement) -> a
  `kotobase.query.bridge`-shaped query descriptor:
  `{:coll-keys [...] :query {:find [...] :where [...]} :columns [...]}`,
  `:columns` in `:find`/row order (`\"n.prop\"` strings, Neo4j-style)."
  [{:keys [pattern where return]} parameters]
  (let [nodes (:nodes pattern)
        rel-type (:rel-type pattern)
        node-clauses (mapv (fn [{:keys [var label]}] [(var-sym var) :kotobase/coll label]) nodes)
        rel-clauses (when rel-type
                      (let [[a b] nodes
                            fk (fk-attr rel-type)
                            fk-var (symbol (str "?__fk_" (:var a) "_" (:var b)))]
                        [[(var-sym (:var a)) fk fk-var]
                         [(var-sym (:var b)) :kotobase/key fk-var]]))
        where-clauses (mapv (fn [{:keys [var prop value]}]
                              [(var-sym var) (keyword prop) (resolve-value value parameters)])
                            where)
        return-syms (mapv (fn [{:keys [var prop]}] (symbol (str "?" var "__" prop))) return)
        return-clauses (mapv (fn [{:keys [var prop]} rsym] [(var-sym var) (keyword prop) rsym])
                             return return-syms)
        columns (mapv (fn [{:keys [var prop]}] (str var "." prop)) return)]
    {:coll-keys (into [] (distinct) (map :label nodes))
     :query {:find return-syms
             :where (-> []
                        (into node-clauses)
                        (into rel-clauses)
                        (into where-clauses)
                        (into return-clauses))}
     :columns columns}))

;; --------------------------------------------------------------- execute

(defn execute
  "Run a `translate`d query descriptor `{:coll-keys :query}` against
  `store` via `kotobase.query.bridge/query` (materialize + q), filtered by
  the REQUIRED `visible?` predicate. Returns a vector of result rows
  (vectors, `:find`/`:columns` order) sorted by stringified value for
  deterministic output -- v0.1 has no ORDER BY, see ns docstring."
  [store {:keys [coll-keys query]} visible?]
  (let [rows (bridge/query store coll-keys query visible?)]
    (vec (sort-by (fn [row] (mapv str row)) rows))))

;; ----------------------------------------------------------------- HTTP

(defn- json-response [status m]
  (http/response status {"content-type" "application/json"} (json/encode m)))

(defn- audit! [store op statement now extra]
  (st/-append store :kotobase.protocols/audit
              (merge {:surface :cypher :op op :statement statement :now now} extra)))

(defn- run-statement
  "Parse + translate + execute one `{\"statement\" \"...\" \"parameters\"
  {...}}` map. Returns `{:ok true :result {...}}` or `{:ok false :error
  {...}}` -- never throws (all failures inside the Cypher pipeline are
  caught here and turned into the response's `errors` shape)."
  [store visible? now stmt]
  (let [statement (get stmt "statement")
        parameters (or (get stmt "parameters") {})]
    (try
      (if-not (string? statement)
        (throw (param-err "each statement must have a string \"statement\" field"))
        (let [ast (parse statement)
              translated (translate ast parameters)
              rows (execute store translated visible?)]
          (audit! store :query statement now {:row-count (count rows)})
          {:ok true
           :result {"columns" (:columns translated)
                    "data" (mapv (fn [row]
                                   {"row" (vec row) "meta" (vec (repeat (count row) nil))})
                                 rows)}}))
      (catch #?(:clj Exception :cljs :default) e
        (let [data (ex-data e)
              code (or (:cypher/code data) "Neo.ClientError.Statement.SyntaxError")
              msg (or (:cypher/message data) #?(:clj (.getMessage e) :cljs (.-message e)) (str e))]
          (audit! store :query-error statement now {:code code :error msg})
          {:ok false :error {"code" code "message" msg}})))))

(defn handle
  "`POST /db/data/transaction/commit` handler -- Neo4j HTTP Cypher
  transaction-endpoint-shaped (see ns docstring for the exact request/
  response JSON and the v0.1 Cypher subset). `ctx` is
  `{:store IStore :visible? (fn [datom] boolean?) :now optional-ISO-string}`.

  `:visible?` is REQUIRED -- throws immediately (a ctx bug, not a wire
  error) if missing; no permissive default (ADR-2607050500)."
  [{:keys [store visible? now]} req]
  (when-not (fn? visible?)
    (throw (ex-info
            (str "kotobase.protocols.cypher/handle requires ctx :visible? -- a REQUIRED"
                 " predicate fn over materialized datoms, no permissive default"
                 " (ADR-2607050500). Pass (constantly true) to see everything.")
            {:cypher/ctx-error true})))
  (cond
    (not= ["db" "data" "transaction" "commit"] (http/segments (:path req)))
    (http/not-found)

    (not= :post (:method req))
    (http/method-not-allowed)

    :else
    (let [parsed (try (json/parse (or (:body req) "{}"))
                       (catch #?(:clj Exception :cljs :default) _ ::malformed))]
      (cond
        (= parsed ::malformed)
        (json-response 400 {"results" []
                            "errors" [{"code" "Neo.ClientError.Request.InvalidFormat"
                                       "message" "malformed JSON body"}]})

        (not (sequential? (get parsed "statements")))
        (json-response 400 {"results" []
                            "errors" [{"code" "Neo.ClientError.Request.InvalidFormat"
                                       "message" "body must be {\"statements\": [...]}"}]})

        :else
        (loop [stmts (get parsed "statements") results []]
          (if (empty? stmts)
            (json-response 200 {"results" results "errors" []})
            (let [{:keys [ok result error]} (run-statement store visible? now (first stmts))]
              (if ok
                (recur (rest stmts) (conj results result))
                ;; A statement error aborts the whole transaction, matching
                ;; the real HTTP Cypher API's auto-commit rollback-on-error
                ;; semantics -- remaining statements never run.
                (json-response 200 {"results" results "errors" [error]})))))))))
