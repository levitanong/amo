(ns amo.middleware
  (:require [clojure.core.async.impl.protocols :refer [ReadPort]]
            [clojure.core.async :as a :refer [go <! >! put! chan close! timeout] :include-macros true]))

(defn wrap-map-handler
  [handler handler-map]
  (fn [app effect-id effect-data]
    (if-let [map-handler (get handler-map effect-id)]
      (map-handler app effect-id effect-data)
      (handler app effect-id effect-data))))

(defn wrap-async
  "If the output of handler is a channel, take from it.
    Regardless, pass this value to handle-chan"
  [handler handle-chan]
  (fn [app effect-id effect-data]
    (let [maybe-tx-chan (handler app effect-id effect-data)]
      (if (and maybe-tx-chan (satisfies? ReadPort maybe-tx-chan))
        (go (handle-chan app effect-id effect-data (<! maybe-tx-chan)))
        (handle-chan app effect-id effect-data maybe-tx-chan)))))