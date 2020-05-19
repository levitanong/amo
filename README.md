# Amo

## Installation
In your deps
```clj
levitanong/amo {:git/url "https://github.com/levitanong/amo.git"
                :sha     "323536114e29ff5130b121430734e9f0f505aef6"}
```
Replace the value of :sha with the current hash.

## Quick Start
Below is a snippet of heavily commented code that showcases the simplest cases
of most of Amo's features.

```clj
(require '[amo.core :as amo])

(defmulti read-handler (fn [_ read-key _] read-key))

;; Simplest read-handler. 
;; Will attempt to get the value of `read-key` straight from state-map
;; Ignore dep-map for now. It will be explained later
(defmethod read-handler :default
  [state-map read-key dep-map]
  (get state-map read-key))

(defmulti mut-handler (fn [event _ _] event))

;; Just increment some field in state.
;; We make it a path instead of a keyword because it's more flexible.
(defmethod mut-handler :inc
  [event {:keys [field-path]} state-map]
  (update-in state-map field-path inc))

(defn fake-http
  [app effect]
  (js/setTimeout 
    (fn [e]
      (amo/transact! app [[:merge-foo {:foo 0}]]))
    100))

(def app
  (amo/new-app
    {:state (atom {:foo 0})
     :read-handler read-handler
     :mutation-handler mut-handler}))

;; RUM SPECIFIC
(require '[rum.core :as rum])

(rum/defc Root
  < (amo/rum-subscribe #{:foo})
  [app {:keys [foo]}]
  [:div "foo: " foo])

(rum/mount (Root app) (. js/document (getElementById "app")))
```