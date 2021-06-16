(ns amo.core
  "State management lib for the discerning Filipino.
   Inspired by ideas from om.next, fulcro, rum, and citrus.
   "
  (:require
   [amo.protocols :as p]
   [amo.specs :as specs]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.core.async :refer [put! >! chan] :as a]
   [ghostwheel.core :as g :refer [>defn >defn- >fdef => | <- ?]]))

(s/def ::deref-key keyword?)
(s/def ::ref-key keyword?)
(s/def ::deref-handler fn?)
(s/def ::ref any?)

(s/def ::store
  (s/keys :req-un [::ref] :req-opt [::ref-key ::deref-key ::deref-handler]))

(s/def ::stores (s/map-of keyword? ::store))

(s/def ::read-dependencies
  (s/map-of keyword? (s/coll-of keyword? :kind set?)))

(s/def ::read-handler
  (s/and ifn? #(instance? MultiFn %)))

(s/def ::mutation-handler ifn?)

(s/def ::effect-handler ifn?)

(s/def ::effect-handlers
  (s/map-of keyword? ::effect-handler))

(s/def ::app-user-config
  (s/keys :req-un
    [::stores
     #_::read-handler
     #_::mutation-handler]
    :opt-un
    [::effect-handlers]))

(defmulti mutation-handler (fn [_state event _params] event))
(defmulti read-handler (fn [_state read-key _params] read-key))

(defmethod mutation-handler ::refresh
  [_ _ read-keys]
  {:refresh read-keys})

(defn- resolve-reads
  ([env reads] (resolve-reads env reads {}))
  ([{:keys [derefed-stores read-handler extra-env] :as env} [read-to-execute & reads-pending-execution] results]
    (cond
    ;; nothing more to evaluate.
      (nil? read-to-execute) results
    ;; read-to-execute already had a value. 
    ;; Likely because a dependent already calculated this.
      (contains? results read-to-execute) (resolve-reads env reads-pending-execution results)
    ;; Not found in results, so need to evaluate.
      :else (let [;; if it's a parametrized read, we split it into the key and params.
                  [read-key read-params] (if (list? read-to-execute)
                                           read-to-execute
                                           [read-to-execute nil])
                ;; get the deps of read-key, since the deps are declared on the read-key
                ;; because defreads are dispatched on the read-key, not the read as a whole.
                ;; HOWEVER, the read cache (or read-values) is keyed on the read as a whole
                ;; because it's a cache of values.
                ;; resolve those deps.
                  result                 (read-handler (merge extra-env derefed-stores)
                                           read-key
                                           read-params)]
              (resolve-reads env
                reads-pending-execution
                ;; We key this on read-to-execute instead of read-key
                ;; because we want to cache on the "instance" level.
                ;; What we want to store into the cache is: 
                ;; "the value of this read for this param."
                (assoc results read-to-execute result))))))

(defn- deref-stores
  "Assuming all the stores in :stores are atom-like, 
    simply create a new map with all the derefed values as values."
  [stores]
  (into {}
    (map (fn [[key store]]
           (let [{:keys [ref-key deref-key deref-handler ref]
                  :or {ref-key key
                       deref-key key
                       deref-handler deref}} store]
             [deref-key (deref-handler ref)]))
      stores)))

(defn default-effect-handler
  [{:keys [app]} effect-key effect-data]
  (case effect-key
    :state (let [state (get-in app [:stores :state :ref])
                 new-state effect-data]
             (swap! state (fn [_old-state] new-state))
             nil)
    :refresh (let [refreshes (:refreshes app)
                   new-read-keys effect-data]
               (swap! refreshes
                 (fn [r]
                   (into (or r #{}) new-read-keys)))
               nil)
    (js/console.error
      (ex-info "No handler for effect-key"
        {:effect-key effect-key
         :effect-data effect-data}))))

(defrecord App
  [stores tx-queue subscribers pending-schedule
   schedule-fn release-fn
   read-values all-read-keys
   mutation-handler read-handler effect-handlers effect-handler
   refreshes extra-env]
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
              prioritize-stores (fn [v]
                                  (cond
                                    (contains? stores v) 0
                                    (= v :refresh) 1
                                    :else 2))]
          ;; Apply side effects including store mutations
          (doseq [[tx-key tx-params :as tx] txs
                  :when tx]
            (let [effects (mutation-handler (merge extra-env (deref-stores stores)) tx-key tx-params)
                  effect-ids (sort-by prioritize-stores (keys effects))]
              ;; Apply side effects
              (doseq [effect-id effect-ids]
                (let [effect-data (get effects effect-id)
                      env {:app this
                           :tx tx}]
                  (effect-handler env effect-id effect-data)))))
          ;; Execute refreshes
          ;; Go through all subscribers, see if any of them care about pending refreshes.
          (let [pending-rereads @refreshes
                _ (reset! refreshes #{})
                ;; all-read-keys represents all the derived keys
                ;; Taken from all the dispatch values of the multimethod `read-handler`,
                ;; sans the `:default` dispatch.

                ;; Subscribers also directly specify the set of read-keys
                ;; they care about. What we want from this is the root read-keys
                ;; and not derived read-keys. Good thing we use a set, so deduping is free.
                all-reads (reduce (fn [acc {:subscriber/keys [read-keys]}]
                                    (into acc read-keys))
                            #_(set all-read-keys)
                            #{}
                            (vals @subscribers))
                ;; The problem is subscribers don't "subscribe" until they're mounted on the component.
                ;; So it's a race condition between subscribing, and whatever mutation happens.
                reads-to-execute (if (contains? pending-rereads ::all)
                                   ;; The moment a mutation wants to refresh everything, we refresh everything.
                                   all-reads
                                   ;; Otherwise, flesh out the pending rereads with `read-dependents`
                                   ;; Which is derived from the dependency map of read-keys.
                                   pending-rereads)
                ; _ (js/console.log "reads asdf" all-read-keys (map :subscriber/read-keys (vals @subscribers)))
                ;; read-values is an atom of the mapping from read-key 
                ;; to the result of exeuting the `read-handler`.
                ;; read-values will be used by subscribers to access the data they need.
                ;; This way, we avoid unnecessary repeat executions of read-handler.

                ;; We deref it now, to get the previous values.
                prev-values @read-values
                ;; Given `reads-to-execute`, evaluate `read-handler` to create a map
                ;; that can be used to reset! `read-values`.
                derefed-stores (deref-stores stores)
                new-values (resolve-reads {:derefed-stores derefed-stores
                                           :read-handler read-handler
                                           :extra-env extra-env}
                             reads-to-execute)
                ;; We merge prev-values and new-values to get the complete map.
                new-read-values (merge prev-values new-values)]
            ;; reset! read-values with this merger.
            (reset! read-values new-read-values)
            ;; Notify subscribers who care about a read-key inside reads-to-execute to rerender.
            (doseq [[_ {:subscriber/keys [id read-keys render]}] @subscribers]
              (when (and (seq (set/intersection read-keys reads-to-execute))
                      ;; Still check because race conditions happen
                      (contains? @subscribers id))
                (render (select-keys prev-values read-keys)
                  (select-keys new-read-values read-keys))))))))))

;; PUBLIC API
(defn transact! [app mutations] (p/-transact! app mutations))
(defn add-subscriber! [app subscriber] (p/-add-subscriber! app subscriber))
(defn remove-subscriber! [app id] (p/-remove-subscriber! app id))
(defn amo-app? [app] (satisfies? p/AmoApp app))

(defn default-schedule-fn
  [f]
  (if (.-hidden js/document)
    (f)
    (js/requestAnimationFrame f)))

(defn default-release-fn
  [id]
  (js/cancelAnimationFrame id))

(defn new-app
  [config]
  (when-not (s/valid? ::app-user-config config)
    (js/console.error "Invalid app config"
      (s/explain-data ::app-user-config config)))
  (let [all-read-keys     (->> read-handler
                               methods
                               keys
                               (remove (partial = :default)))
        new-config        (merge
                            {:tx-queue          (atom [])
                             :pending-schedule  (volatile! nil)
                             :subscribers       (atom {})
                             :refreshes         (atom #{})
                             :schedule-fn       default-schedule-fn
                             :release-fn        default-release-fn
                             :all-read-keys     all-read-keys
                             :mutation-handler  mutation-handler
                             :read-values       (atom {})
                             :read-handler      read-handler}
                            config)]
    (map->App new-config)))


