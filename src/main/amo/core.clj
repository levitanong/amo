(ns amo.core
  (:require
   [clojure.spec.alpha :as s]
   [amo.specs :as specs]
   [amo.protocols :as p]))

(s/def ::app symbol?)

(s/def ::deps ::specs/dependencies)

(s/def ::options
  (s/keys :req-un [::app ::deps]))

(s/def ::defread
  (s/cat :sym simple-symbol?
         :dispatch-key keyword?
         :options ::options
         :args-list vector?
         :body (s/* any?)))

(defmacro defmultiread
  [sym]
  (let [read-key-sym (gensym)]
    `(defmulti sym
       (fn [~'_ ~read-key-sym ~'_] ~read-key-sym))))

(defmacro defread
  [& args]
  (when-not (s/valid? ::defread args)
    (throw (ex-info "Invalid args for defread."
                    {:spec-error
                     (s/explain-data ::defread args)})))
  (let [{:keys [sym dispatch-key options args-list body]} (s/conform ::defread args)
        {:keys [app deps]} options]
    `(do
       ;; Register dependencies of this read
       (swap! (:read-dependencies ~app) assoc ~dispatch-key ~deps)
       (defmethod ~sym ~dispatch-key
         ~args-list
         ~@body))))