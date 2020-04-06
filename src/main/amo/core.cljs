(ns amo.core
  "State management lib for the discerning Filipino.
   Inspired by ideas from om.next, fulcro, rum, and citrus.
   "
  (:require
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.core.async :refer [put! >! chan] :as a]
   [rum.core :as rum])
  (:require-macros
   [clojure.core.async :refer [go]]))

;; A path to some value in the app state as if by `get-in`.
(s/def ::read-path (s/coll-of keyword? :kind vector?))

(s/def ::derived-read-key keyword?)

(s/def ::primitive-read-key
       (s/or :key keyword?
             :path ::read-path))

;; Read keys are the dispatch values for the `read-handler` multimethod.
;; They may either be a keyword or a vector of keywords.
;; If it's a keyword, it's either a derived read, or a top-level value read.
;; If it's a vector of keywords, it's a path to a value in state
;; as if by using `get-in`.
(s/def ::read-key
  (s/or :key ::derived-read-key
        :path ::primitive-read-key))

(s/def ::dependencies
       (s/coll-of ::read-key :kind set?))

(s/def ::dependency-map
       (s/map-of ::read-key ::dependencies))

(s/def ::subscriber
       (s/keys :req [:subscriber/id
                     :subscriber/read-keys
                     :subscriber/render]))

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

(defprotocol ITransact
  (transact! [this mutations]
    "Adds mutations to a global tx-queue, and schedule the execution of that queue
     based on `schedule-fn`'s discretion."))

(defprotocol ISchedule
  (schedule! [this f] 
    "If there is a pending schedule, clear it, then schedule f for execution.
     Relies on the fact that `schedule-fn` returns an ID, and `release-fn` takes an ID
     to clear a pending schedule."))

(defprotocol IPublisher
  (add-subscriber! [this subscriber]
    "A method to be called by a component to subscribe to changes in state.
     subscriber must at least have the following keys 
     `#{:subscriber/id :subscriber/read-keys :subscriber/rerender}`")
  (remove-subscriber! [this id]
    "Stop listening to state changes"))

(defprotocol AmoApp
  (amo-app? [this]))

(defrecord App 
           [state tx-queue subscribers pending-schedule 
            schedule-fn release-fn 
            read-dependencies read-dependents reads
            read-values all-read-keys
            mutation-handler read-handler effect-handlers]
  AmoApp
  (amo-app? [this] true)
  IPublisher
  (add-subscriber! [this subscriber]
    (swap! subscribers
           (fn [s]
             (assoc (or s {})
                    (:subscriber/id subscriber)
                    subscriber))))
  (remove-subscriber! [this id]
    (swap! subscribers
           (fn [s]
             (dissoc (or s {}) id))))
  ISchedule
  (schedule! [this f]
    (when-let [id @pending-schedule]
      (vreset! pending-schedule nil)
      (release-fn id))
    (vreset! pending-schedule (schedule-fn f)))
  ITransact
  (transact! [this mutations]
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
    (schedule! this
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

(defn new-app
  [config]
  (let [new-config          (if-let [read-dependencies (:read-dependencies config)]
                              (assoc config :read-dependents
                                     (-> read-dependencies
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