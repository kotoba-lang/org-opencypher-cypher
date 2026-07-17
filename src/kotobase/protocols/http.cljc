(ns kotobase.protocols.http
  "Ring-shaped request/response plumbing for the cypher.kotobase.net
  surface.

  VENDORED, not a dependency: ADR-2607172300's dependency table lists only
  `kotoba-lang/kotobase-query` as this repo's dependency (not
  `kotoba-lang/kotobase-protocols`) — this namespace is copied verbatim
  from `kotobase-protocols`' `kotobase.protocols.http` (same namespace
  name, deliberately, since it is the exact same tiny zero-dependency
  ring-shape contract every `kotobase.protocols.*` HTTP surface in this
  org shares) rather than pulled in as a git dependency on a whole sibling
  protocols repo just for this one file. Keep in sync by hand if
  `kotobase-protocols`' version changes in a way that matters here.

  A request is plain data:
    {:method  :get|:put|:post|:head|:delete
     :host    \"cypher.kotobase.net\"        ; optional (router uses it)
     :path    \"/db/data/transaction/commit\"
     :query   {\"prefix\" \"a/\"}          ; string keys, string values
     :headers {\"content-type\" \"...\"}   ; lower-case string keys
     :body    \"...\"}                      ; string body (v0.1; binary is a follow-up)

  A response is {:status int :headers {...} :body string-or-nil}.
  Handlers are pure: (handle ctx req) → resp, where ctx carries the
  injected kotobase.store/IStore under :store (LocalStore standalone,
  KotobaseStore against kotobase.net — the store seam never leaks into
  handler logic)."
  (:require [clojure.string :as str]))

(defn segments
  "Path → vector of decoded, non-empty segments: \"/a//b\" → [\"a\" \"b\"]."
  [path]
  (->> (str/split (or path "") #"/")
       (remove str/blank?)
       vec))

(defn query-param [req k] (get (:query req) k))

(defn header [req k] (get (:headers req) (str/lower-case k)))

(defn response
  ([status headers body] {:status status :headers headers :body body})
  ([status body] (response status {} body)))

(defn text [status body]
  (response status {"content-type" "text/plain; charset=utf-8"} body))

(defn not-found
  ([] (not-found "not found"))
  ([msg] (text 404 msg)))

(defn method-not-allowed [] (text 405 "method not allowed"))

(defn xml-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))
