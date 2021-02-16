(ns amo.rum
  (:require [amo.core :as amo]
            [ghostwheel.core :as g :refer [>defn >defn- >fdef => | <- ?]]
            [clojure.spec.alpha :as s]
            [rum.core :as rum]
            [clojure.set :as set]))

(defn rum-state->amo-app
  [state]
  (->> state
       :rum/args
       #_(filter amo-app?)
       (filter (fn [arg] (contains? arg ::app)))
       first))

(>defn rum-subscribe
  [read-f]
  [(s/or
     :read-keys set?
     :read-f (s/fspec
               :args (s/cat :args (s/* any?))
               :ret (s/coll-of :amo.specs/read-key :kind set?))) => any?]
  (let [id (random-uuid)]
    {:will-mount   (fn [state]
                     (let [{:rum/keys [react-component args]} state
                           {::keys [app]}                     (rum-state->amo-app state)
                           read-keys                          (cond
                                                                (set? read-f) read-f
                                                                (fn? read-f) (apply read-f args))]
                       (amo/add-subscriber! app
                         {:subscriber/id        id
                          :subscriber/read-keys read-keys
                          :subscriber/render    (fn [_prev-props _props]
                                                  (rum/request-render react-component))})
                       (assoc state
                         :amo.subscriber/id id
                         :amo.subscriber/read-keys read-keys)))
     :wrap-render  (fn [render-fn]
                     (fn [rum-state]
                       (let [{::keys [app]}                                             (rum-state->amo-app rum-state)
                             {:amo.subscriber/keys [read-keys] :rum/keys [args]}        rum-state
                             {:keys [state read-handler read-values read-dependencies]} app
                             values                                                     @read-values
                             state-map                                                  @state
                             missing-keys                                               (set/difference read-keys (set (keys values)))
                             ;; because of quirks in the Rum lifecycle, deref gets called before the watcher add-watch happens.
                             ;; This means that when deref is first called, the subscribers atom is not updated.
                             ;; This also means that primitive-read-keys in the component that uses this atom won't be
                             ;; included in all-read-keys. To compensate, we simply get the difference between
                             ;; the keys of `read-values` and the read-keys we have here.
                             props                                                      (reduce (fn [props missing-read]
                                                                                                  (let [[read-key read-params] (if (list? missing-read)
                                                                                                                                 missing-read
                                                                                                                                 [missing-read nil])]
                                                                                                    (assoc props missing-read
                                                                                                      (read-handler state-map
                                                                                                        read-dependencies
                                                                                                        read-key
                                                                                                        read-params))))
                                                                                          (select-keys values read-keys)
                                                                                          missing-keys)]
                         (render-fn (-> rum-state
                                        (update :rum/args
                                          (fn [args]
                                            ;; (into [props] args)
                                            (map (fn [arg]
                                                   (if (contains? arg ::app)
                                                     (assoc arg ::props props)
                                                     arg)
                                                   #_(if (amo-app? arg)
                                                       (into acc [props arg])
                                                       (conj acc arg)))
                                              args))))))))
     :will-unmount (fn [state]
                     (let [{::keys [app]} (rum-state->amo-app state)]
                       (amo/remove-subscriber! app id)
                       (-> state
                           (dissoc :amo.subscriber/id)
                           (dissoc :amo.subscriber/read-keys))))}))

#_(>defn rum-subscribe2
    [read-f]
    [(s/fspec
       :args (s/cat :props any?)
       :ret (s/coll-of ::specs/read-key :kind set?)) => any?]
    (let [id (random-uuid)]
      {:init         (fn [state _props]
                       (let [{:rum/keys [react-component args]} state
                             amo-app                            (rum-state->amo-app state)
                             read-keys                          (apply read-f args)]
                         (add-subscriber! amo-app
                           {:subscriber/id        id
                            :subscriber/read-keys read-keys
                            :subscriber/render    (fn [_prev-props _props]
                                                    (rum/request-render react-component))})
                         (assoc state
                           :amo.subscriber/id id
                           :amo.subscriber/read-keys read-keys)))
       :wrap-render  (fn [render-fn]
                       (fn [rum-state]
                         (let [app                                      (rum-state->amo-app rum-state)
                               {:amo.subscriber/keys [read-keys]}       rum-state
                               {:keys [read-handler read-values state]} app
                               values                                   @read-values
                               state-map                                @state
                               missing-keys                             (set/difference read-keys (set (keys values)))
                             ;; because of quirks in the Rum lifecycle, deref gets called before the watcher add-watch happens.
                             ;; This means that when deref is first called, the subscribers atom is not updated.
                             ;; This also means that primitive-read-keys in the component that uses this atom won't be
                             ;; included in all-read-keys. To compensate, we simply get the difference between
                             ;; the keys of `read-values` and the read-keys we have here.
                               props                                    (reduce (fn [props missing-key]
                                                                                  (assoc props missing-key (read-handler state-map
                                                                                                             {}
                                                                                                             missing-key
                                                                                                             nil)))
                                                                          (select-keys values read-keys)
                                                                          missing-keys)]
                           (render-fn (-> rum-state
                                          (update :rum/args
                                            (fn [args]
                                              (reduce (fn [acc arg]
                                                        (if (amo-app? arg)
                                                          (into acc [arg props])
                                                          (conj acc arg)))
                                                []
                                                args))))))))
       :will-unmount (fn [state]
                       (let [amo-app (rum-state->amo-app state)]
                         (remove-subscriber! amo-app id)
                         (-> state
                             (dissoc :amo.subscriber/id)
                             (dissoc :amo.subscriber/read-keys))))}))

#_(defn rum-subscribe
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
                         (add-subscriber! amo-app
                           {:subscriber/id        id
                            :subscriber/read-keys read-keys
                            :subscriber/render    (fn [_prev-props _props]
                                                    (rum/request-render react-component))}))
                       (assoc state
                         :amo.subscriber/id id
                         :amo.subscriber/read-keys read-keys))
       :wrap-render  (fn [render-fn]
                       (fn [rum-state]
                         (let [app                                      (rum-state->amo-app rum-state)
                               {:keys [read-handler read-values state]} app
                               values                                   @read-values
                               state-map                                @state
                               missing-keys                             (set/difference read-keys (set (keys values)))
                             ;; because of quirks in the Rum lifecycle, deref gets called before the watcher add-watch happens.
                             ;; This means that when deref is first called, the subscribers atom is not updated.
                             ;; This also means that primitive-read-keys in the component that uses this atom won't be
                             ;; included in all-read-keys. To compensate, we simply get the difference between
                             ;; the keys of `read-values` and the read-keys we have here.
                               props                                    (reduce (fn [props missing-key]
                                                                                  (assoc props missing-key (read-handler state-map missing-key)))
                                                                          (select-keys values read-keys)
                                                                          missing-keys)]
                           (render-fn (-> rum-state
                                          (update :rum/args
                                            (fn [args]
                                              (reduce (fn [acc arg]
                                                        (if (amo-app? arg)
                                                          (into acc [arg props])
                                                          (conj acc arg)))
                                                []
                                                args))))))))
       :will-unmount (fn [state]
                       (let [amo-app (rum-state->amo-app state)]
                         (remove-subscriber! amo-app id)
                         (-> state
                             (dissoc :amo.subscriber/id)
                             (dissoc :amo.subscriber/read-keys))))}))

;; DEPRECATED
#_(def subscribe rum-subscribe)

;; DEPRECATED
#_(deftype ReadCursor [app id read-keys meta]
    Object
    (equiv [this other]
      (-equiv this other))

    IAtom

    IMeta
    (-meta [_] meta)

    IEquiv
    (-equiv [this other]
      (identical? this other))

    IDeref
    (-deref [_]
      (let [{:keys [read-handler read-values stores]} app
            values                                   @read-values
            missing-keys                             (set/difference read-keys (set (keys values)))]
      ;; because of quirks in the Rum lifecycle, deref gets called before the watcher add-watch happens.
      ;; This means that when deref is first called, the subscribers atom is not updated.
      ;; This also means that primitive-read-keys in the component that uses this atom won't be
      ;; included in all-read-keys. To compensate, we simply get the difference between
      ;; the keys of `read-values` and the read-keys we have here.
        (reduce (fn [props missing-key]
                  (assoc props missing-key (read-handler (deref-stores stores) missing-key {})))
          (select-keys values read-keys)
          missing-keys)))


    IWatchable
    (-add-watch [this key callback]
              ;; key is specific to the component rendering this cursor.
              ;; Which means, if there are several cursor on the same component
              ;; they'll all have the same key.
      (reset! id key)
      (when (= read-keys #{:all-agencies})
        (js/console.log "all-agencies watch"))
      (add-subscriber! app
        {:subscriber/id        @id
         :subscriber/read-keys read-keys
         :subscriber/render    (fn [prev-props props]
                                 (callback this @id prev-props props))})
      this)

    (-remove-watch [this key]
      (remove-subscriber! app @id)
      this)

    IHash
    (-hash [this] (goog/getUid this))

    IPrintWithWriter
    (-pr-writer [this writer opts]
      (-write writer "#object [amo.core.ReadCursor]")
      (pr-writer {:val (-deref this)} writer opts)
      (-write writer "]")))

;; DEPRECATED
#_(defn subscribe-reads
    ([app read-keys]
      (subscribe-reads app
        {:id        (atom nil)
         :read-keys read-keys}
        {}))
    ([app {:keys [id read-keys]} {:keys [meta]
                                  :as   options}]
      (->ReadCursor app id read-keys meta)))