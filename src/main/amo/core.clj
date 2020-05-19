(ns amo.core
  (:require
   [clojure.spec.alpha :as s]
   [amo.specs :as specs]
   [amo.protocols :as p]))

(s/def ::app-sym symbol?)

(s/def ::deps ::specs/dependencies)

(s/def ::options
  (s/keys :req-un [::deps]))

(s/def ::defread
  (s/cat :dispatch-key keyword?
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
  (let [{:keys [dispatch-key options args-list body]} (s/conform ::defread args)
        {:keys [deps]} options
        fq-read-sym 'amo.core/read-handler]
    `(do
       ;; Register dependencies of this read
       (swap! amo.core/colocated-read-dependencies assoc ~dispatch-key ~deps)
       (defmethod ~fq-read-sym ~dispatch-key
         ~args-list
         ~@body))))

(defmacro defmutation
  [& args]
  (when-not (s/valid? ::defmutation args)
    (throw (ex-info "Invalid args for defmutation"
                    {:spec-error
                     (s/explain-data ::defmutation args)})))
  (let [{:keys [dispatch-key args-list body]} (s/conform ::defmutation args)
        fq-mutate-sym 'amo.core/mutate-handler]
    `(defmethod ~fq-mutate-sym ~dispatch-key
       ~args-list
       ~@body)))