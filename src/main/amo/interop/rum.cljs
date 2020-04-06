(ns amo.interop.rum 
  (:require
   [amo.core :as amo]
   [clojure.set :as set]
   [rum.core :as rum]))

(defn rum-state->amo-app
  [state]
  (->> state
       :rum/args
       (filter (fn [arg]
                 (satisfies? amo/AmoApp arg)))
       first))

(defn reactive
  "Mixin. Works in conjunction with [[react]].
  
   ```
   (rum/defc comp < rum/reactive
     [*counter]
     [:div (rum/react counter)])
   (def *counter (atom 0))
   (rum/mount (comp *counter) js/document.body)
   (swap! *counter inc) ;; will force comp to re-render
   ```"
  [read-keys]
  (let [id (random-uuid)]
  {:init         (fn [state _props]
                   (let [{:rum/keys [react-component]} state
                         amo-app                       (rum-state->amo-app state)]
                     (amo/add-subscriber! amo-app
                                      {:subscriber/id        id
                                       :subscriber/read-keys read-keys
                                       :subscriber/render    (fn [_prev-props _props]
                                                               (rum/request-render react-component))}))
                   (assoc state 
                          :amo.subscriber/id id
                          :amo.subscriber/read-keys read-keys))
     :wrap-render  (fn [render-fn]
                     (fn [state]
                       (let [app                                (rum-state->amo-app state)
                             {:keys [read-handler read-values]} app
                             values                             @read-values
                             missing-keys                       (set/difference read-keys (set (keys values)))
                             ;; because of quirks in the Rum lifecycle, deref gets called before the watcher add-watch happens.
                             ;; This means that when deref is first called, the subscribers atom is not updated.
                             ;; This also means that primitive-read-keys in the component that uses this atom won't be
                             ;; included in all-read-keys. To compensate, we simply get the difference between
                             ;; the keys of `read-values` and the read-keys we have here.
                             props                              (reduce (fn [props missing-key]
                                                                          (assoc props missing-key (read-handler app missing-key)))
                                                                        (select-keys values read-keys)
                                                                        missing-keys)]
                         (render-fn (-> state
                                        (update :rum/args
                                                (fn [args]
                                                  (reduce (fn [acc arg]
                                                            (if (satisfies? amo/AmoApp arg)
                                                              (into acc [arg props])
                                                              (conj acc arg)))
                                                          []
                                                          args))))))))
     :will-unmount (fn [state]
                     (let [amo-app (rum-state->amo-app state)]
                       (amo/remove-subscriber! amo-app id)
                       (-> state
                           (dissoc :amo.subscriber/id)
                           (dissoc :amo.subscriber/read-keys))))}))