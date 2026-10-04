(ns jepsen.d_engine.lock
  "Lock workload: a lock built from compare_and_swap must never be held by two
   threads at once.

   The lock is one key. 0 means free, any other value is the owner's token.
   acquire swaps 0 -> token, release swaps token -> 0.

   Every operation completes as :ok or :fail, never :info. An operation whose
   outcome is unknown would stay concurrent with everything after it, and a
   mutex search over many of those does not finish (it ran out of memory). A
   client that does not get a confirmation treats the lock as not its own and
   never uses it, so :fail is the honest result for the client.

   What this gives up: an acquire that did succeed but was not confirmed is
   recorded as a failure, so a second holder at that moment would not show in the
   history. The next acquire attempt by the same thread frees such a lock."
  (:require [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]]
            [knossos.model :as model]
            [jepsen.d_engine.client :as grpc]))

(def lock-key 7)

(defn owner-token
  "Token of the thread an operation runs on."
  [test op]
  (inc (mod (:process op) (:concurrency test))))

(defn run-op
  "Runs one lock operation. cas is (fn [expected new]) returning what cas!
   returns; held is an atom, true while this client believes it holds the lock."
  [cas held op token]
  (let [release-own! (fn [] (cas token 0))]
    (case (:f op)
      :acquire
      (if @held
        (assoc op :type :fail :error :already-held)
        (let [res (cas 0 token)]
          (if (and (= :ok (:type res)) (:swapped res))
            (do (reset! held true)
                (assoc op :type :ok))
            ;; Not taken, or not confirmed. If it was taken after all, the lock
            ;; is ours without us knowing: give it back.
            (do (release-own!)
                (assoc op :type :fail
                          :error (if (= :ok (:type res)) :not-swapped (:error res)))))))

      :release
      (if-not @held
        (assoc op :type :fail :error :not-held)
        (let [res (release-own!)]
          ;; Whatever came back, the critical section is over and this client
          ;; will not use the lock again. A release that did not go through is
          ;; repeated by the next acquire attempt.
          (reset! held false)
          (assoc op :type :ok))))))

(defrecord LockClient [endpoints channels held]
  client/Client

  (open! [this test node]
    (assoc this :channels (grpc/open-all-channels endpoints)
                :held     (atom false)))

  ;; Runs for every client before the first operation of the test.
  (setup! [this test]
    (grpc/put! channels lock-key 0))

  (invoke! [this test op]
    (run-op (fn [expected new] (grpc/cas! channels lock-key expected new))
            held op (owner-token test op)))

  (teardown! [this test])

  (close! [this test]
    (when channels (grpc/close-all-channels channels))))

(defn workload [opts]
  {:client    (LockClient. (:endpoints opts) nil nil)
   :checker   (checker/compose
                {:linear (checker/linearizable {:model     (model/mutex)
                                                :algorithm :linear})})
   ;; Every thread alternates acquire and release.
   :generator (gen/each-thread
                (gen/stagger 1/5
                             (gen/cycle [{:type :invoke, :f :acquire, :value nil}
                                         {:type :invoke, :f :release, :value nil}])))})
