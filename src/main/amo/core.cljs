(ns amo.core
  "State management lib for the discerning Filipino.
   Inspired by ideas from om.next, fulcro, rum, and citrus.
   "
  (:require
   [amo.protocols :as p]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.core.async :refer [put! >! chan] :as a]
   [rum.core :as rum])
  (:require-macros
   [clojure.core.async :refer [go]]))

(defn resolve-read-key
  "Recursively replace a `read-key` with a set of root root-read-keys.
   By default, also includes the derived-read-key (a key that has an entry in `dep-map`)
   so that mutations that explicitly refresh the derived-read-key still 
   trigger a recalculation for that derived-read-key.
   This behavior can be deactivated with `:include-derived? false`"
  [dep-map read-key & {:keys [include-derived?] :or {include-derived? true}}]
  (if-not (contains? dep-map read-key)
    ;; Can't be found, return the read-key itself in a set.
    #{read-key}
    ;; Found, recursively call `resolve-read-key` on the
    ;; dependencies to resolve them. Then collect into a set.
    (into (if include-derived? #{read-key} #{})
          (map (fn [read-key-dep]
                 ;; returns a set.
                 (resolve-read-key dep-map read-key-dep 
                                   :include-derived? include-derived?)))
          (get dep-map read-key))))

(defn resolve-dep-map
  "Given some dependency map, resolve each set of dependencies
   by either augmenting or replacing derived-read-keys
   with their entries in the dependency map. Whether the operation
   is augmentation or replacement is based on :include-derived?,
   which is true by default.
   
   e.g. {:foo #{:bar :baz} :bar #{:bag}}
   
   :include-derived? true
   {:foo #{:bar :bag :baz} :bar #{:bag}}
   
   :include-derived? false
   {:foo #{:bag :baz} :bar #{:bag}}"
  [dep-map & {:keys [include-derived?] :or {include-derived? true}}]
  (into {}
        (map (fn [[read-key dependencies]]
               [read-key (reduce (fn [acc dep-read-key]
                                   (into acc (resolve-read-key dep-map dep-read-key
                                                               :include-derived? include-derived?)))
                                 #{}
                                 dependencies)]))
        dep-map))

(defn dependencies->dependents
  "Converts the a mapping from dependent to dependencies
  into a mapping from dependency to dependents."
  [read-dependencies]
  (reduce (fn [acc [dependent dependencies]]
            (reduce (fn [acc2 dependency]
                      ;; Associate the dependents to the dependency
                      (update acc2 dependency
                              (fn [dependents]
                                (conj (or dependents #{})
                                      dependent))))
                    acc
                    dependencies))
          {}
          read-dependencies))

(defrecord App 
           [state tx-queue subscribers pending-schedule 
            schedule-fn release-fn 
            read-dependencies read-dependents reads
            read-values all-read-keys
            mutation-handler read-handler effect-handlers]
  p/AmoApp
  (p/-amo-app? [this] true)
  p/IPublisher
  (p/-add-subscriber! [this subscriber]
    (swap! subscribers
           (fn [s]
             (assoc (or s {})
                    (:subscriber/id subscriber)
                    subscriber))))
  (p/-remove-subscriber! [this id]
    (swap! subscribers
           (fn [s]
             (dissoc (or s {}) id))))
  p/ISchedule
  (p/-schedule! [this f]
    (when-let [id @pending-schedule]
      (vreset! pending-schedule nil)
      (release-fn id))
    (vreset! pending-schedule (schedule-fn f)))
  p/ITransact
  (p/-transact! [this mutations]
             ;; schedule! will only run at most once per animation frame
             ;; this is why we swap into tx-queue so that we can keep track
             ;; of all the transactions that have happened since then.
             ;; You could say that schedule! is a like a cargo train that departs 
             ;; at regular intervals only if there is cargo. This is a useful metaphor,
             ;; so this is what we'll use to comment this code. 

             ;; accept cargo in cargo hold
    (swap! tx-queue
           (fn [queue]
             (into (or queue []) mutations)))
             ;; Don't worry about several schedules happening at a time.
             ;; Each time a new transaction is scheduled, any pending schedules
             ;; get cancelled, and the new schedule takes on the responsibility
             ;; of the old one.
    (p/-schedule! this
               (fn [_]
                 ;; Train is leaving. `txs` is the collection of cargo that made it
                 ;; onto the train.
                 (let [txs       @tx-queue
                       ;; cargo has been transfered to train, so now the platform is empty.
                       ;; Our train metaphor can now end.
                       _         (reset! tx-queue [])
                       refreshes (atom #{})]
                   ;; Update state and apply side effects
                   (swap! state
                          (fn [old-state]
                            (reduce (fn [st [tx-key tx-params]]
                                      (let [effect-map (mutation-handler tx-key tx-params st)
                                            new-state  (:state effect-map)
                                            tx-refresh (:refresh effect-map)
                                            effects    (-> effect-map (dissoc :state) (dissoc :refresh))]
                                        ;; Collect refreshes
                                        (swap! refreshes
                                               (fn [r]
                                                 (into (or r #{}) tx-refresh)))
                                        ;; Apply side effects
                                        (doseq [[effect-id effect-data] effects]
                                          (let [effect-handler (get effect-handlers effect-id)]
                                            (when-not effect-handler
                                              (throw (ex-info "No handler for effect found" {:effect-id effect-id})))
                                            (effect-handler this effect-data)))
                                        (or new-state st)))
                                    old-state
                                    txs)))
                   ;; Execute refreshes
                   ;; Go through all subscribers, see if any of them care about pending refreshes.
                   (let [pending-rereads @refreshes
                         ;; all-read-keys represents all the derived keys
                         ;; Taken from all the dispatch values of the multimethod `read-handler`,
                         ;; sans the `:default` dispatch.

                         ;; Subscribers also directly specify the set of read-keys
                         ;; they care about. What we want from this is the root read-keys
                         ;; and not derived read-keys. Good thing we use a set, so deduping is free.
                         all-reads (reduce (fn [acc {:subscriber/keys [read-keys]}]
                                             (into acc read-keys))
                                           (set all-read-keys)
                                           (vals @subscribers))
                         reads-to-execute (if (contains? pending-rereads ::all)
                                            ;; The moment a mutation wants to refresh everything, we refresh everything.
                                            all-reads
                                            ;; Otherwise, flesh out the pending rereads with `read-dependents`
                                            ;; Which is derived from the dependency map of read-keys.
                                            (reduce (fn [acc read-to-update]
                                                      (if-let [dependents (seq (get read-dependents read-to-update))]
                                                        (into acc dependents)
                                                        acc))
                                                    pending-rereads
                                                    pending-rereads))
                         ;; read-values is an atom of the mapping from read-key 
                         ;; to the result of exeuting the `read-handler`.
                         ;; read-values will be used by subscribers to access the data they need.
                         ;; This way, we avoid unnecessary repeat executions of read-handler.

                         ;; We deref it now, to get the previous values.
                         prev-values @read-values
                         ;; Given `reads-to-execute`, evaluate `read-handler` to create a map
                         ;; that can be used to reset! `read-values`.
                         new-values2 (loop [read-to-execute reads-to-execute
                                            eval-cache      (atom {})]
                                       (let [cache @eval-cache
                                             ]
                                         (if (contains? cache read-to-execute)
                                           )))
                         new-values (into {}
                                      (map (fn [read-key]
                                             [read-key (read-handler this read-key)]))
                                      reads-to-execute)
                         ;; We merge prev-values and new-values to get the complete map.
                         new-read-values (merge prev-values new-values)]
                     ;; reset! read-values with this merger.
                     (reset! read-values new-read-values)
                     ;; Notify subscribers who care about a read-key inside reads-to-execute to rerender.
                     (doseq [[_ {:subscriber/keys [read-keys render]}] @subscribers]
                       (when (seq (set/intersection read-keys reads-to-execute))
                         (render (select-keys prev-values read-keys)
                                 (select-keys new-read-values read-keys))))))))))

;; PUBLIC API
(defn transact! [app mutations] (p/-transact! app mutations))
(defn add-subscriber! [app subscriber] (p/-transact! app subscriber))
(defn remove-subscriber! [app id] (p/-remove-subscriber! app id))
(defn amo-app? [app] (p/-amo-app? app))

(defn new-app
  [config]
  (let [new-config          (if-let [read-dependencies (:read-dependencies config)]
                              (assoc config :read-dependents
                                     (-> (if (instance? Atom read-dependencies)
                                           (deref read-dependencies)
                                           read-dependencies)
                                         (resolve-dep-map)
                                         (dependencies->dependents)))
                              config)
        all-read-keys       (->> (:read-handler config)
                                 methods
                                 keys
                                 (remove (partial = :default)))
        app                 (map->App (-> new-config
                                          (merge {:tx-queue         (atom [])
                                                  :pending-schedule (volatile! nil)
                                                  :subscribers      (atom {})
                                                  :schedule-fn      js/requestAnimationFrame
                                                  :release-fn       js/cancelAnimationFrame
                                                  :all-read-keys    all-read-keys
                                                  :read-values      (atom {})})))]
    app))


(defn rum-state->amo-app
  [state]
  (->> state
       :rum/args
       (filter amo-app?)
       first))

(defn rum-subscribe
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
(def subscribe rum-subscribe)

;; DEPRECATED
(deftype ReadCursor [app id read-keys meta]
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
    (let [{:keys [read-handler read-values]} app
          values                             @read-values
          missing-keys                       (set/difference read-keys (set (keys values)))]
      ;; because of quirks in the Rum lifecycle, deref gets called before the watcher add-watch happens.
      ;; This means that when deref is first called, the subscribers atom is not updated.
      ;; This also means that primitive-read-keys in the component that uses this atom won't be
      ;; included in all-read-keys. To compensate, we simply get the difference between
      ;; the keys of `read-values` and the read-keys we have here.
      (reduce (fn [props missing-key]
                (assoc props missing-key (read-handler app missing-key)))
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
(defn subscribe-reads
  ([app read-keys]
   (subscribe-reads app
                    {:id        (atom nil)
                     :read-keys read-keys}
                    {}))
  ([app {:keys [id read-keys]} {:keys [meta]
                                :as   options}]
   (->ReadCursor app id read-keys meta)))