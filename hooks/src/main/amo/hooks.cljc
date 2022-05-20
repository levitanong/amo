(ns amo.hooks
  (:require
   [amo.core :as amo]
   [amo.util]
   [uix.core.alpha :as uix]
   [clojure.set :as set]))

(defn subscribe!
  ([app read-keys]
    (subscribe! app read-keys #?(:clj nil :cljs (random-uuid))))
  ([app read-keys id]
    (let [init-props (select-keys @(:read-values app) read-keys)
          props* (uix/state init-props)
          normalized-read-keys (if (map? read-keys)
                                 (set (vals read-keys))
                                 read-keys)
          sorted-read-keys (into []
                             (sort-by (fn [read-key]
                                        (if (sequential? read-key)
                                          (first read-key)
                                          read-key))
                               normalized-read-keys))
          read-key->alias (when (map? read-keys)
                            (set/map-invert read-keys))]
      (uix/effect!
        (fn []
          (amo/add-subscriber! app
            {:subscriber/id        id
             :subscriber/read-keys normalized-read-keys
             :subscriber/render    (fn [_prev-props new-props]
                                     ;; Possibly redundant check, but better be sure.
                                     (when (contains? @(:subscribers app) id)
                                       (reset! props*
                                         (if-not (map? read-keys)
                                           new-props
                                           (into {}
                                             (map (fn [[query data]]
                                                    [(get read-key->alias query) data]))
                                             new-props)))))})
          (amo/transact! app
            [[::amo/refresh normalized-read-keys]])
          (fn []
            ;; cleanup
            (amo/remove-subscriber! app id)))
        sorted-read-keys #_[sorted-read-keys])
      @props*)))