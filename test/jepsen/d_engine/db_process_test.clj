(ns jepsen.d-engine.db-process-test
  (:require [clojure.test :refer [deftest is]]
            [jepsen.control :as c]
            [jepsen.d_engine.db :as db]
            [slingshot.slingshot :refer [throw+]]))

(defn- pgrep-exit [code]
  (fn [& _] (throw+ {:type :jepsen.control/nonzero-exit :exit code})))

(deftest running-is-true-when-pgrep-finds-a-process
  (with-redefs [c/exec (fn [& _] "1234")]
    (is (true? (db/running?)))))

(deftest running-is-false-only-when-pgrep-reports-no-match
  (with-redefs [c/exec (pgrep-exit 1)]
    (is (false? (db/running?)))))

(deftest running-does-not-hide-other-failures
  (with-redefs [c/exec (pgrep-exit 2)]
    (is (thrown? Throwable (db/running?))))
  (with-redefs [c/exec (fn [& _] (throw (RuntimeException. "ssh connection lost")))]
    (is (thrown? RuntimeException (db/running?)))))

(deftest await-stopped-returns-once-the-process-is-gone
  (let [answers (atom [true true false])]
    (with-redefs [db/running? (fn [] (let [a (first @answers)] (swap! answers rest) a))]
      (is (= :stopped (db/await-stopped! 5000))))))

(deftest await-stopped-fails-when-the-process-survives
  (with-redefs [db/running? (fn [] true)]
    (is (thrown? clojure.lang.ExceptionInfo (db/await-stopped! 250)))))
