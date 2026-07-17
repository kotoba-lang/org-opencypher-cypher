;; nbb test runner — first-class runtime per repo rule (kotoba wasm >
;; clojurewasm > cljs > nbb > (jvm/bb)). Run from the repo root:
;;
;;   nbb --classpath "src:test:.deps/kotobase-query/src:.deps/kotobase/src:.deps/arrangement/src:.deps/prolly-tree/src:.deps/io-ipld/src:.deps/io-multiformats/src:.deps/org-ietf-cbor/src" bin/run_tests.cljs
;;
;; where every .deps/<name> is a checkout of the matching kotoba-lang repo
;; at the SHA pinned in deps.edn (kotobase-query) or transitively in
;; kotobase-query's / arrangement's own deps.edn (kotobase, arrangement,
;; prolly-tree, io-ipld, io-multiformats, org-ietf-cbor) — see deps.edn's
;; comment and kotobase-query's own README for why each hop is on the
;; classpath. CI pins every one of them to the same SHAs. `npm install`
;; this repo's package.json first (transitive @noble/hashes dep, see
;; package.json comment).
(ns run-tests
  (:require [cljs.test :as t]
            [kotobase.protocols.cypher-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (set! (.-exitCode js/process) 1)))

(t/run-tests 'kotobase.protocols.cypher-test)
