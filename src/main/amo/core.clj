(ns amo.core
  (:require
   [clojure.spec.alpha :as s]
   [amo.specs :as specs]
   [amo.protocols :as p]))

(s/def ::app-sym symbol?)

(s/def ::deps ::specs/dependencies)

(s/def ::options
  (s/keys :opt-un [::deps]))

(s/def ::parametrized-read
  (s/cat :read-key keyword? :param-key keyword?))

(s/def ::read-dispatch
  (s/or :simple keyword? :parametrized ::parametrized-read))

(s/def ::defread
  (s/cat :dispatch-key ::read-dispatch
         :options (s/? ::options)
         :args-list vector?
         :body (s/* any?)))

(s/def ::defmutation
  (s/cat :dispatch-key keyword?
         :args-list vector?
         :body (s/* any?)))

(defmacro defread
  [& args]
  (when-not (s/valid? ::defread args)
    (throw (ex-info "Invalid args for defread."
                    {:spec-error
                     (s/explain-data ::defread args)})))
  (let [{:keys [dispatch-key options args-list body]
         :or {options {:deps #{}}}} (s/conform ::defread args)
        {:keys [deps]} options
        fq-read-sym 'amo.core/read-handler
        dispatch (second dispatch-key)]
    `(do
       ;; Register dependencies of this read
       (set! amo.core/*read-deps*
         (assoc amo.core/*read-deps* ~dispatch
           ~(into (empty deps)
              ;; Have to do this because conform turns s/or into tuples.
              (map second)
              deps)))
       (defmethod ~fq-read-sym ~dispatch
         ~args-list
         ~@body))))

(defmacro defmutation
  [& args]
  (when-not (s/valid? ::defmutation args)
    (throw (ex-info "Invalid args for defmutation"
                    {:spec-error
                     (s/explain-data ::defmutation args)})))
  (let [{:keys [dispatch-key args-list body]} (s/conform ::defmutation args)
        fq-mutate-sym 'amo.core/mutation-handler]
    `(defmethod ~fq-mutate-sym ~dispatch-key
       ~args-list
       ~@body)))

(defn add-subscriber!
  "No op. Dev experience for cljc only"
  [& args])

(defn remove-subscriber!
  "No op. Dev experience for cljc only"
  [& args])

(defn transact!
  "No op. Dev experience for cljc only"
  [& args])