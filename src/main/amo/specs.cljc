(ns amo.specs
  (:require
   [clojure.spec.alpha :as s])
  #?(:clj
     (:import
      (clojure.lang Atom))))

;; A path to some value in the app state as if by `get-in`.
(s/def ::read-path (s/coll-of keyword? :kind vector?))

;; Read keys are the dispatch values for the `read-handler` multimethod.
;; They may either be a keyword or a vector of keywords.
;; If it's a keyword, it's either a derived read, or a top-level value read.
;; If it's a vector of keywords, it's a path to a value in state
;; as if by using `get-in`.
(s/def ::read-key
  (s/or :primitive-key keyword?
        :path-key ::read-path))

(s/def ::dependencies
  (s/coll-of ::read-key :kind set?))

(s/def ::dependency-map
  (s/map-of ::read-key ::dependencies))

(s/def ::subscriber
  (s/keys :req [:subscriber/id
                :subscriber/read-keys
                :subscriber/render]))

;; state tx-queue subscribers pending-schedule
;; schedule-fn release-fn
;; read-dependencies read-dependents reads
;; read-values all-read-keys
;; mutation-handler read-handler effect-handlers

;; (defn atom? [a] (instance? Atom a))

;; (s/def ::state atom?)

;; (s/def ::state )