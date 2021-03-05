(ns amo.middleware
  (:require [clojure.core.async.impl.protocols :refer [ReadPort]]
            [clojure.core.async :as a :refer [go <! >! put! chan close! timeout] :include-macros true]))

(defn wrap-map-handler
  [handler handler-map]
  (fn [env effect-id effect-data]
    (if-let [map-handler (get handler-map effect-id)]
      (map-handler env effect-id effect-data)
      (handler env effect-id effect-data))))

(defn wrap-async
  "If the output of handler is a channel, take from it.
    Regardless, pass this value to handle-chan"
  [handler handle-chan]
  (fn [env effect-id effect-data]
    (let [maybe-tx-chan (handler env effect-id effect-data)]
      (if (and maybe-tx-chan (satisfies? ReadPort maybe-tx-chan))
        (go (handle-chan env effect-id effect-data (<! maybe-tx-chan)))
        (handle-chan env effect-id effect-data maybe-tx-chan)))))