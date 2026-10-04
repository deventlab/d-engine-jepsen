(ns jepsen.d-engine.five-voters-test
  (:require [clojure.test :refer [deftest is]]
            [jepsen.d_engine :refer [check-five-voters!]]
            [jepsen.d_engine.db :refer [config-path data-dir]]))

(def five-nodes ["node1" "node2" "node3" "node4" "node5"])

(deftest config-path-default-keeps-the-three-voter-layout
  (is (= "/app/config/n1" (config-path {} "node1")))
  (is (= "/app/config/n4" (config-path {:five-voters false} "node4"))))

(deftest config-path-five-voters-uses-its-own-directory
  (is (= "/app/config/five/n1" (config-path {:five-voters true} "node1")))
  (is (= "/app/config/five/n5" (config-path {:five-voters true} "node5"))))

(deftest config-path-unknown-node-fails
  (is (thrown? clojure.lang.ExceptionInfo (config-path {} "node9"))))

(deftest data-dir-does-not-depend-on-the-mode
  (is (= "/app/db/3" (data-dir "node3"))))

(deftest five-voters-needs-five-nodes
  (is (nil? (check-five-voters! {:five-voters true :nodes five-nodes})))
  (is (thrown? clojure.lang.ExceptionInfo
               (check-five-voters! {:five-voters true :nodes ["node1" "node2" "node3"]}))))

(deftest five-voters-rejects-lazyfs
  (is (thrown? clojure.lang.ExceptionInfo
               (check-five-voters! {:five-voters true :lazyfs true :nodes five-nodes}))))

(deftest default-mode-is-not-restricted
  (is (nil? (check-five-voters! {:nodes ["node1" "node2" "node3"]})))
  (is (nil? (check-five-voters! {:five-voters false :lazyfs true :nodes ["node1"]}))))
