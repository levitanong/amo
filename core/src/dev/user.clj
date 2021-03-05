(ns user
  (:require [shadow.cljs.devtools.api :as shadow]
            [shadow.cljs.devtools.server :as server]
            [shadow.cljs.devtools.config :as config]
            [clojure.java.io :as io]))

(defn cljs-start
  [build-id]
  (server/start!)
  (shadow/watch build-id))

(defn cljs-repl-connect
  [build-id]
  (shadow/nrepl-select build-id))