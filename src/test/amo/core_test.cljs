(ns amo.core-test
  (:require
   [amo.core :as amo]
   [clojure.test :as test :refer [deftest testing is]]))

(binding [amo.core/*read-deps* {}]
  (def sample-state-map
    {:root/field1 0
     :root/foo-table {0 {:foo/id 0}
                      1 {:foo/id 1}}})
  
  (amo/defread :default
    {:deps #{}}
    [state-map deps read-key read-params]
    (if (vector? read-key)
      (get-in state-map read-key)
      (get state-map read-key)))
  
  (amo/defread :field1-inc
    {:deps #{:root/field1}}
    [state-map {:root/keys [field1]} read-key read-params]
    (inc field1))
  
  (amo/defread :specific-foo
    {:deps #{:root/foo-table}}
    [state-map {:root/keys [foo-table]} read-key read-params]
    (get foo-table (:id read-params)))
  
  (println "asdf" amo.core/*read-deps*)
  
  (let [env {:state-map    sample-state-map
             :dep-map      amo.core/*read-deps*
             :read-handler amo.core/read-handler}]

    (deftest test-resolve-reads
      (testing "empty reads"
        (is (= {}
               (amo/resolve-reads env #{}))))
      (testing "nil reads"
        (is (= {}
               (amo/resolve-reads env nil))))
      (testing "read field directly"
        (is (= {:root/field1 0}
               (amo/resolve-reads env #{:root/field1}))))
      (testing "read field1-inc"
        (is (= {:field1-inc  1
                :root/field1 0}
               (amo/resolve-reads env 
                 #{:field1-inc}))))
      (testing "read path"
        (is (= {[:root/foo-table 0] {:foo/id 0}}
               (amo/resolve-reads env
                 #{[:root/foo-table 0]}))))
      (testing "parametrized read"
        (is (= {'(:specific-foo {:id 0}) {:foo/id 0}
                :root/foo-table {0 {:foo/id 0}
                                 1 {:foo/id 1}}}
               (amo/resolve-reads env
                 #{'(:specific-foo {:id 0})})))))))

