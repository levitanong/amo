# Amo

## Installation
In your deps
```clj
levitanong/amo.core {:git/url "https://github.com/levitanong/amo.git"
                     :deps/root "core"
                     :sha "bafad5dbb4933024e91009a1dc349747726d850b"}
;; Optionally use a preset subscribe! implementation
levitanong/amo.hooks {:git/url "https://github.com/levitanong/amo.git"
                      :deps/root "hooks"
                      :sha "bafad5dbb4933024e91009a1dc349747726d850b"}
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
;; Ignore params for now. It will be explained later.
(defmethod read-handler :default
  [{:keys [state]} read-key params]
  (get state read-key))

(defmulti mut-handler (fn [_ event _] event))

;; Just increment some field in state.
;; We make it a path instead of a keyword because it's more flexible.
(defmethod mut-handler :inc
  [{:keys [state]} _event {:keys [field-path]}]
  (update-in state field-path inc))

(defn fake-http
  [{:keys [app]} effect-id effect]
  (js/setTimeout 
    (fn [e]
      (amo/transact! app [[:merge-foo {:foo 0}]]))
    100))

(def app
  (amo/new-app
    {:stores {:state (atom {:foo 0})}
     :read-handler read-handler
     :mutation-handler mut-handler}))
```