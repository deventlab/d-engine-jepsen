(ns jepsen.d-engine.client-test
  (:require [clojure.test :refer [deftest is]]
            [jepsen.d_engine.client :refer [find-endpoint]]))

(deftest find-endpoint-normal
  (is (= "node2:9082"
         (find-endpoint "http://node1:9081,http://node2:9082,http://node3:9083" "node2"))))

(deftest find-endpoint-fallback-no-npe
  ;; Before fix: NPE. After fix: returns first endpoint.
  (is (= "node1:9081"
         (find-endpoint "http://node1:9081,http://node2:9082" "node99"))))

(deftest find-endpoint-strips-trailing-slash
  (is (= "node1:9081"
         (find-endpoint "http://node1:9081/" "node1"))))
