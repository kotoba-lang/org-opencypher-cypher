(ns kotobase.protocols.cypher-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.local :as local]
            [kotobase.protocols.cypher :as cypher]
            [kotobase.protocols.json :as json]
            [kotobase.store :as st]))

(def everything (constantly true))

(defn- fixture-store
  "users (label \"users\") + departments (label \"departments\"), a
  relationship convention fixture: users carry a :works_at attribute
  matching the DEPT->attribute convention (rel-type \"WORKS_AT\" ->
  attribute :works_at) whose value is a department's :kotobase/key."
  []
  (let [s (local/local-store)]
    (st/-put s "users" "u1" {:name "Alice" :role "admin" :works_at "d1"})
    (st/-put s "users" "u2" {:name "Bob" :role "user" :works_at "d2"})
    (st/-put s "users" "u3" {:name "Carol" :role "admin" :works_at "d1"})
    (st/-put s "users" "u4" {:name "Dave" :role "user"}) ; no :works_at
    (st/-put s "departments" "d1" {:name "Engineering" :budget 900000})
    (st/-put s "departments" "d2" {:name "Sales" :budget 400000})
    s))

(defn- ctx
  ([store] {:store store :visible? everything :now "2026-07-17T00:00:00Z"})
  ([store visible?] {:store store :visible? visible? :now "2026-07-17T00:00:00Z"}))

(defn- commit! [c statements]
  (cypher/handle c {:method :post :path "/db/data/transaction/commit"
                    :body (json/encode {"statements" statements})}))

(defn- single [c statement & [parameters]]
  (commit! c [(cond-> {"statement" statement} parameters (assoc "parameters" parameters))]))

;; ------------------------------------------------------------- parse

(deftest parse-basic-match-where-return
  (let [ast (cypher/parse "MATCH (n:users) WHERE n.role = 'admin' RETURN n.name, n.role")]
    (is (= [{:var "n" :label "users"}] (:nodes (:pattern ast))))
    (is (= [{:var "n" :prop "role" :value "admin"}] (:where ast)))
    (is (= [{:var "n" :prop "name"} {:var "n" :prop "role"}] (:return ast)))))

(deftest parse-return-only-no-where
  (let [ast (cypher/parse "MATCH (n:users) RETURN n.name")]
    (is (= [] (:where ast)))
    (is (= [{:var "n" :prop "name"}] (:return ast)))))

(deftest parse-multiple-and-predicates
  (let [ast (cypher/parse "MATCH (n:users) WHERE n.role = 'admin' AND n.name = 'Alice' RETURN n.name")]
    (is (= 2 (count (:where ast))))))

(deftest parse-numeric-boolean-null-and-negative-values
  (is (= 900000 (:value (first (:where (cypher/parse "MATCH (d:departments) WHERE d.budget = 900000 RETURN d.name"))))))
  (is (= true (:value (first (:where (cypher/parse "MATCH (n:users) WHERE n.active = true RETURN n.name"))))))
  (is (= nil (:value (first (:where (cypher/parse "MATCH (n:users) WHERE n.deleted = null RETURN n.name"))))))
  (is (= -5 (:value (first (:where (cypher/parse "MATCH (n:users) WHERE n.score = -5 RETURN n.name")))))))

(deftest parse-relationship-pattern
  (let [ast (cypher/parse "MATCH (u:users)-[:WORKS_AT]->(d:departments) RETURN u.name, d.name")]
    (is (= ["u" "d"] (map :var (:nodes (:pattern ast)))))
    (is (= "WORKS_AT" (:rel-type (:pattern ast))))))

(deftest parse-parameter-value
  (let [ast (cypher/parse "MATCH (n:users) WHERE n.role = $role RETURN n.name")]
    (is (= {:cypher/param "role"} (:value (first (:where ast)))))))

;; ----------------------------------------------------- parse rejections

(deftest reject-create-merge-delete-with-clear-message
  (doseq [kw ["CREATE" "MERGE" "DELETE" "SET" "REMOVE"]]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          (re-pattern (str kw ".*not supported"))
                          (cypher/parse (str kw " (n:users) RETURN n.name"))))))

(deftest reject-unlabeled-node
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                        #"has no label"
                        (cypher/parse "MATCH (n) RETURN n.name"))))

(deftest reject-variable-length-relationship
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                        #"variable-length"
                        (cypher/parse "MATCH (a:users)-[:KNOWS*1..3]->(b:users) RETURN a.name"))))

(deftest reject-multi-hop-pattern
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                        #"multi-hop"
                        (cypher/parse "MATCH (a:users)-[:KNOWS]->(b:users)-[:KNOWS]->(c:users) RETURN a.name"))))

(deftest reject-undirected-and-reverse-relationships
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                        #"directed rightward"
                        (cypher/parse "MATCH (a:users)-[:KNOWS]-(b:users) RETURN a.name"))))

(deftest reject-unknown-variable-in-return
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                        #"unknown variable"
                        (cypher/parse "MATCH (n:users) RETURN x.name"))))

(deftest reject-unknown-variable-in-where
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                        #"unknown variable"
                        (cypher/parse "MATCH (n:users) WHERE x.role = 'admin' RETURN n.name"))))

(deftest reject-empty-return
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                        #"at least one"
                        (cypher/parse "MATCH (n:users) RETURN"))))

(deftest reject-trailing-garbage
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                        #"unexpected trailing"
                        (cypher/parse "MATCH (n:users) RETURN n.name ORDER BY n.name"))))

;; --------------------------------------------------------- end-to-end

(deftest end-to-end-equality-filter
  (let [c (ctx (fixture-store))
        res (single c "MATCH (n:users) WHERE n.role = 'admin' RETURN n.name, n.role")
        body (json/parse (:body res))]
    (is (= 200 (:status res)))
    (is (= [] (get body "errors")))
    (is (= 1 (count (get body "results"))))
    (let [r (first (get body "results"))]
      (is (= ["n.name" "n.role"] (get r "columns")))
      (is (= #{["Alice" "admin"] ["Carol" "admin"]}
             (into #{} (map #(get % "row") (get r "data")))))
      (is (every? #(= [nil nil] (get % "meta")) (get r "data"))))))

(deftest end-to-end-no-where-returns-everyone
  (let [c (ctx (fixture-store))
        body (json/parse (:body (single c "MATCH (n:users) RETURN n.name")))
        r (first (get body "results"))]
    (is (= #{["Alice"] ["Bob"] ["Carol"] ["Dave"]}
           (into #{} (map #(get % "row") (get r "data")))))))

(deftest end-to-end-multiple-and-predicates
  (let [c (ctx (fixture-store))
        body (json/parse (:body (single c "MATCH (n:users) WHERE n.role = 'admin' AND n.name = 'Alice' RETURN n.name")))
        r (first (get body "results"))]
    (is (= [["Alice"]] (map #(get % "row") (get r "data"))))))

(deftest end-to-end-parameter-substitution
  (let [c (ctx (fixture-store))
        body (json/parse (:body (single c "MATCH (n:users) WHERE n.role = $role RETURN n.name"
                                        {"role" "user"})))
        r (first (get body "results"))]
    (is (= #{["Bob"] ["Dave"]} (into #{} (map #(get % "row") (get r "data")))))))

(deftest end-to-end-missing-parameter-errors
  (let [c (ctx (fixture-store))
        body (json/parse (:body (single c "MATCH (n:users) WHERE n.role = $role RETURN n.name")))]
    (is (= [] (get body "results")))
    (is (= 1 (count (get body "errors"))))
    (is (re-find #"missing parameter" (get (first (get body "errors")) "message")))))

(deftest end-to-end-numeric-equality
  (let [c (ctx (fixture-store))
        body (json/parse (:body (single c "MATCH (d:departments) WHERE d.budget = 900000 RETURN d.name")))
        r (first (get body "results"))]
    (is (= [["Engineering"]] (map #(get % "row") (get r "data"))))))

;; ---------------------------------------------- relationship join (bonus)

(deftest end-to-end-relationship-join
  (testing "-[:WORKS_AT]-> joins users.works_at against departments.:kotobase/key
    -- a real cross-collection join through kotobase-query's bridge, exercising
    a genuine capability, not a stub"
    (let [c (ctx (fixture-store))
          body (json/parse (:body (single c "MATCH (u:users)-[:WORKS_AT]->(d:departments) RETURN u.name, d.name")))
          r (first (get body "results"))]
      (is (= #{["Alice" "Engineering"] ["Bob" "Sales"] ["Carol" "Engineering"]}
             (into #{} (map #(get % "row") (get r "data"))))
          "Dave (no :works_at) correctly drops out of the join, same as the
          underlying bridge's own join semantics"))))

;; -------------------------------------------------------- transaction

(deftest end-to-end-second-statement-fails-aborts-transaction
  (let [c (ctx (fixture-store))
        body (json/parse (:body (commit! c [{"statement" "MATCH (n:users) RETURN n.name"}
                                             {"statement" "CREATE (n:users) RETURN n"}
                                             {"statement" "MATCH (n:users) RETURN n.role"}])))]
    (is (= 1 (count (get body "results"))) "first statement ran and is kept")
    (is (= 1 (count (get body "errors"))) "second statement's error is reported")
    (is (re-find #"CREATE" (get (first (get body "errors")) "message")))
    (is (nil? (second (get body "results"))) "third statement never ran")))

(deftest end-to-end-all-statements-succeed
  (let [c (ctx (fixture-store))
        body (json/parse (:body (commit! c [{"statement" "MATCH (n:users) RETURN n.name"}
                                             {"statement" "MATCH (d:departments) RETURN d.name"}])))]
    (is (= 2 (count (get body "results"))))
    (is (= [] (get body "errors")))))

;; -------------------------------------------------------------- HTTP

(deftest wrong-method-is-405
  (let [c (ctx (fixture-store))]
    (is (= 405 (:status (cypher/handle c {:method :get :path "/db/data/transaction/commit"}))))))

(deftest wrong-path-is-404
  (let [c (ctx (fixture-store))]
    (is (= 404 (:status (cypher/handle c {:method :post :path "/not/cypher"}))))))

(def ^:private bad-json "{oops")

(deftest malformed-json-body-is-400
  (let [c (ctx (fixture-store))
        req {:method :post :path "/db/data/transaction/commit" :body bad-json}
        res (cypher/handle c req)]
    (is (= 400 (:status res)))
    (is (= 1 (count (get (json/parse (:body res)) "errors"))))))

(deftest missing-statements-field-is-400
  (let [c (ctx (fixture-store))
        res (cypher/handle c {:method :post :path "/db/data/transaction/commit" :body "{}"})]
    (is (= 400 (:status res)))))

(deftest ctx-without-visible-throws
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (cypher/handle {:store (fixture-store)}
                              {:method :post :path "/db/data/transaction/commit"
                               :body (json/encode {"statements" []})}))
      "handle refuses to run with no stated visibility decision (ADR-2607050500),
      same discipline kotobase.query.bridge enforces"))

;; ---------------------------------------------------- visible? redaction

(deftest visible-redacts-entities
  (let [store (fixture-store)
        no-bob? (fn [{:keys [s]}] (not= s :users/u2))
        c (ctx store no-bob?)
        body (json/parse (:body (single c "MATCH (n:users) RETURN n.name")))
        r (first (get body "results"))]
    (is (not (contains? (into #{} (map #(get % "row") (get r "data"))) ["Bob"])))
    (is (contains? (into #{} (map #(get % "row") (get r "data"))) ["Alice"]))))

;; ------------------------------------------------------------- audit

(deftest audit-trail-records-success-and-error
  (let [store (fixture-store)
        c (ctx store)]
    (single c "MATCH (n:users) RETURN n.name")
    (commit! c [{"statement" "CREATE (n:users) RETURN n"}])
    (let [events (->> (st/-read store :kotobase.protocols/audit 0)
                      (filter #(= :cypher (:surface %))))]
      (is (= [:query :query-error] (map :op events)))
      (is (= "MATCH (n:users) RETURN n.name" (:statement (first events))))
      (is (= 4 (:row-count (first events))))
      (is (string? (:error (second events)))))))
