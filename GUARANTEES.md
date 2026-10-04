# d-engine Correctness Guarantees

Verified by [Jepsen](https://jepsen.io/) testing on d-engine v0.2.5.

---

## 0. How to read this file

- A passing Jepsen run is evidence, not proof. Jepsen finds violations; it cannot show there are none.
- Every scenario was run 3 times, except where a section says otherwise. Three runs have little statistical weight.
- Evidence was produced on a commit that is part of v0.2.5. The node images record `v0.1.2-63-g327938f` and `v0.1.2-59-ge0242b1`; both are the same source tree.
- Each guarantee has the same four parts: the claim, the conditions it was tested under, the evidence, and its limits.
- Status in the overview:
  - **Verified**: tested on v0.2.5 and no violation found.
  - **Observed**: no violation seen, but the test cannot rule it out.
  - **Known issue**: a limit we know about.
  - **Measured only**: numbers are reported, nothing is promised.
  - **Not covered**: not tested. This is the only place that lists what is missing (section 6).
- The run artifacts (`results.edn`, timelines) are kept in the `jepsen/store` directory of the machine that ran them. They are not published yet.

---

## 1. Overview

| Property | Status | Voters | Faults | Details |
| --- | --- | --- | --- | --- |
| Linearizable reads and writes, including reads from an isolated leader | Verified | 5 (3 for `kill`) | kill, leader-isolating partition, frozen leader | 2.1 |
| No split brain under partition | Verified | 3, 5 | partition, pause | 2.2 |
| Acknowledged writes survive power loss | Verified | 3 | leader and one node killed with unflushed data lost | 2.3 |
| Acknowledged writes survive process kill | Verified | 5 | kill | 2.3 |
| One leader at a time | Observed | 3, 5 | all of the above | 2.4 |
| Lock built on compare-and-swap is mutually exclusive | Verified, with a limit | 3 | partition, kill | 2.5 |
| Watch order, scan-then-watch, bank invariant | Verified | 3 (bank also 5) | partition | 2.6 |
| Dynamic membership | Verified | 3 to 5 | partition (`single-learner`: none) | 2.7 |
| Lease renewal with late replies | Known issue | any | none reproduced | F2 |
| Recovery time | Measured only | 3 | partition, kill, pause | 3 |
| Clock faults, lock expiry, snapshot catch-up, more than five voters | Not covered | | | 6 |

---

## 2. Guarantees

### 2.1 Linearizability, including reads from an isolated leader

- **Claim**: reads and writes appear to take effect at one point in time, in the order Raft commits them. A leader that is cut off with one follower, or frozen while the others elect a new leader, does not return a stale value and does not accept writes it cannot commit.
- **Conditions**: 5 voters; faults `kill`, leader-isolating partition (leader plus one follower cut off from the other three), frozen leader. Reads with the lease policy and the linearizable policy. For `kill` also 3 voters.
- **Evidence**: 3/3 PASS each (Knossos): `register-kill-5v`, `register-leader-partition-5v`, `register-lease-leader-partition-5v`, `register-lease-leader-pause-5v`; 3 voters: `register-kill`. See F1 for the bug this found.
- **Limits**: clock faults are not injected (section 6).

### 2.2 No split brain under partition

- **Claim**: during a partition only the majority side accepts writes. After healing the cluster converges and no acknowledged write is lost.
- **Conditions**: 3 and 5 voters; `partition`, and `partition,pause` on 5 voters.
- **Evidence**: 3/3 PASS each: `register-partition-pause-5v`, `bank-5v`, `append-5v` (Elle), `bank`, `set`, `append` (Elle), and the leader-isolating scenarios in 2.1.
- **Limits**: faults are simulated on one host (section 6).

### 2.3 Crash and power-loss durability

- **Claim**: an acknowledged write survives (a) the leader and one more node losing their unflushed data and restarting, repeatedly, and (b) a minority of nodes being killed with SIGKILL and restarted.
- **Conditions**: (a) 3 voters, lazyfs, `append` (Elle), 200 ops/s, 300 s, fault every 5 s. (b) 5 voters, `kill`.
- **Evidence**: (a) `make test-durability`, 3/3 PASS. (b) `register-kill-5v`, 3/3 PASS.
- **Limits**: lazyfs simulates the loss of unflushed data; a real disk or filesystem that ignores fsync is not covered.

### 2.4 One leader at a time

- **Claim**: no two-leader anomaly was observed.
- **Conditions**: all `register` and `register-lease` scenarios in 2.1.
- **Evidence**: a two-leader history would show up as a linearizability violation; none did.
- **Limits**: this is inferred from the absence of a violation, hence **Observed**.

### 2.5 Locks built on compare-and-swap

- **Claim**: a lock built from `compare_and_swap` (acquire: free to owner; release: owner to free) was never held by two threads at once.
- **Conditions**: 3 voters, `partition,kill`, 120 s, Knossos mutex model.
- **Evidence**: `lock`, 3/3 PASS, 119 to 207 successful acquires per run.
- **Limits**: an acquire that succeeded but was not confirmed is recorded as a failure, so a second holder at that moment would not show in the history. d-engine has no lock API (section 6).

### 2.6 Watch, scan-then-watch, bank invariant

- **Claim**:
  - Watch events arrive in strictly increasing commit order.
  - The reconnect pattern (Watch, Scan, drain events newer than the scan) has no gap, no phantom, and monotonic revisions.
  - Concurrent cross-key transfers keep the total balance constant.
- **Conditions**: 3 voters, `partition`, 60 s; `bank` also on 5 voters.
- **Evidence**: `watch`, `scan-watch`, `bank`, `bank-5v`, 3/3 PASS each.
- **Limits**: the buffer-overflow path of `scan-watch` (`RATE=200`) was not run on this commit.

### 2.7 Dynamic membership

- **Claim**: learners can join at runtime. `committed_index` of the membership stream never decreases within a watch window, no node is in both `members` and `learners`, and `members` is never empty.
  - `promotable`: both learners are promoted to voters.
  - `readonly`: read-only learners are never promoted.
  - `single-learner`: a learner that cannot be promoted is evicted without ever entering `members`.
- **Conditions**: `promotable` and `readonly` under `partition`, 60 s; `single-learner` without faults, 420 s.
- **Evidence**: `membership-promotable`, `membership-readonly`, `membership-single-learner`, 3/3 PASS each.
- **Limits**: eviction under faults was not run.

---

## 3. Measurements (no promise)

**Recovery time.** After a fault ends the cluster accepts writes again. **No upper bound is promised.** The time is measured from the end of the fault to the first successful write, as a client sees it. It includes the client's own 5 s call timeout and retries across nodes, and was not split into cluster time and client time.

| Faults | Runs | Fault ends measured | Slowest |
| --- | --- | --- | --- |
| `partition,kill` | 1 | 5 | 1.8 s |
| `partition,kill,pause` (pause freezes all nodes) | 3 | 7 to 12 per run | 22.5 s, 28.3 s, 29.0 s |

---

## 4. Findings

### F1. Isolated leader returned stale values under lease reads

Before the fix, `register-lease` reproduced the stale read in 5 of 5 runs. After the fix, 5 of 5 runs pass (#423). The scenarios stay as regression tests (2.1).

### F2. Known limit in lease renewal

A reply that arrives late is credited to the leader's newest heartbeat round, not the round it answers, so the lease can run up to one heartbeat interval too long. The configuration check keeps a margin between lease length and election timeout; it has not been measured whether the margin absorbs this, and no test has reproduced it.

---

## 5. How we know a pass means something

- The node logs are scanned at the end of every run for fatal errors and panics.
- All nodes must run the same commit.
- A run that did not install the fault it is named after (for example, no leader-isolating partition) is reported invalid, not passed. The same holds for the recovery-time check when no fault ended while writes were being tried.
- The stale-read test fails on the code before the fix (F1: 5 of 5 runs), so a pass is not an artifact of a test that cannot see the bug.

---

## 6. Not covered

| Property | Status |
| --- | --- |
| Clock faults | Not injected. Lease reads assume bounded clock drift between the leader and followers; behavior under skew, jumps, or a slow leader clock is untested (#28). |
| Lock with expiry | Cannot be tested: no conditional write with TTL (#29). |
| Real disk and network | Faults are simulated with iptables and lazyfs on one host. A filesystem that ignores fsync is not covered. |
| Snapshot catch-up of far-behind followers | Verified on v0.2.4 only (section 9); not exercised by the runs above. |
| Membership eviction under faults | `single-learner` runs without faults. |
| More than five voters | Not run. |

---

## 7. Tested configuration

- Nodes: Docker containers on one host; 3 voters, or 5 voters from the start for the `-5v` scenarios; `membership` uses 5 nodes.
- Election timeout: 1000 to 2000 ms on every node.
- `lease_duration_ms` differs per node. In the 3-voter configuration it is 500 on node1 and 100 on node2 and node3. In the 5-voter configuration it is 500 on node1, node4, node5 and 100 on node2, node3. The lease only matters on the node that is leader, so which value applies depends on who is leader at the time. The values come from the earlier configurations and were not unified.
- Client: each call has a 5 s deadline and is retried across nodes.
- Defaults: 10 ops/s, a fault every 10 s, 60 s per run. `make test-durability` uses 200 ops/s, a fault every 5 s, 300 s.

---

## 8. Evidence log (v0.2.5)

| Scenario | Voters | Faults | Runs | Result |
| --- | --- | --- | --- | --- |
| `test-durability` (`append`, lazyfs, 300 s) | 3 | leader+1 kill with power loss | 3 | 3 PASS |
| `register-partition-pause-5v` | 5 | partition, pause | 3 | 3 PASS |
| `register-kill-5v` | 5 | kill | 3 | 3 PASS |
| `bank-5v` | 5 | partition | 3 | 3 PASS |
| `append-5v` | 5 | partition | 3 | 3 PASS |
| `register-leader-partition-5v` | 5 | leader+1 partition | 3 | 3 PASS |
| `register-lease-leader-partition-5v` | 5 | leader+1 partition | 3 | 3 PASS |
| `register-lease-leader-pause-5v` | 5 | frozen leader | 3 | 3 PASS |
| `register-kill` | 3 | kill | 3 | 3 PASS |
| `bank` | 3 | partition | 3 | 3 PASS |
| `set` | 3 | partition | 3 | 3 PASS |
| `append` | 3 | partition | 3 | 3 PASS |
| `watch` | 3 | partition | 3 | 3 PASS |
| `scan-watch` | 3 | partition | 3 | 3 PASS |
| `membership-promotable` | 3 to 5 | partition | 3 | 3 PASS |
| `membership-readonly` | 3 to 5 | partition | 3 | 3 PASS |
| `membership-single-learner` (420 s) | 3 to 4 | none | 3 | 3 PASS |
| `lock` | 3 | partition, kill | 3 | 3 PASS |
| `register` (recovery time) | 3 | partition, kill, pause | 3 | 3 PASS (report only) |

The node images record `v0.1.2-63-g327938f` (durability, five voters) and `v0.1.2-59-ge0242b1` (three-voter scenarios, lock, recovery); both are the same source tree.

---

## 9. History

Results from v0.2.4 (6-hour `set` soak with `FAULTS=all`, 120 s workload runs, snapshot catch-up, membership and scan-watch runs from May 2026) were produced before the #423 fixes. They are kept for reference only and no guarantee above rests on them.

---

## 10. Reproduce

`make build`, then `make test` (all scenarios), `make test-durability`, and `make run-workload WORKLOAD=lock FAULTS=partition,kill`.

- Jepsen 0.3.5.
- Checkers: Knossos, Elle, set-full, and custom checkers for watch, scan-watch, membership, bank and recovery time.
