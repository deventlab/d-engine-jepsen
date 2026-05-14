# d-engine Correctness Guarantees

Verified by [Jepsen](https://jepsen.io/) testing on d-engine v0.2.4.

---

## What d-engine Guarantees

### 1. Linearizability

Every read/write operation appears to execute atomically at a single point in real time, consistent with the global commit order imposed by Raft. A read always returns the most recently committed value — no stale reads, no phantom writes.

**Verified by**: `register` workload (Knossos checker), `append` workload (Elle strict-serializable checker).

### 2. Partition Tolerance — No Split-Brain

During a network partition, at most one partition (the majority) can accept writes. The minority partition rejects writes and serves no stale reads. After the partition heals, the cluster converges to a single consistent state without data loss.

**Verified by**: all workloads under `FAULTS=partition` with majority, minority, and primaries-isolated topologies. The minority partition correctly rejects writes and returns errors — no split-brain, no stale reads served as current.

### 3. Crash Durability

A write acknowledged by the leader is durable: it survives leader crashes, follower crashes, and simultaneous minority crashes. No acknowledged write is ever lost or rolled back.

**Verified by**: all workloads under `FAULTS=kill` (SIGKILL on minority nodes, with restart).

### 4. Single-Leader Safety

At most one node acts as leader per Raft term. Two leaders can never both accept writes concurrently, preventing conflicting committed log entries.

**Verified by**: `register` workload (Knossos linearizability checker would detect any two-leader anomaly as a non-linearizable history).

### 5. Snapshot Recovery

A follower that falls arbitrarily far behind (missed log entries, killed and restarted) recovers to the current cluster state via snapshot transfer. After recovery it participates correctly in consensus.

**Verified by**: `set` workload under extended `FAULTS=kill,partition` — lagged followers rejoin and serve correct reads after recovery.

### 6. Watch Stream Ordering

The Watch streaming RPC delivers PUT events in strictly increasing commit order within each subscription window. No backward jumps occur; events reflect Raft's total commit ordering.

**Verified by**: `watch` workload (custom checker, per-window strict ordering).

### 7. Account Balance Invariant

Concurrent cross-key transfers preserve the total balance across all accounts. No money is created or destroyed, even under concurrent writes and partial failures.

**Verified by**: `bank` workload (balance invariant checker).

---

## Fault Coverage

All guarantees above hold under the following simultaneously injected faults:

| Fault              | Description                                                                  |
| ------------------ | ---------------------------------------------------------------------------- |
| Network partition  | iptables-based isolation: majority split, minority split, primaries isolated |
| Node crash         | SIGKILL on minority nodes, automatic process restart                         |
| Process suspension | SIGSTOP / SIGCONT simulating a frozen or slow node                           |

Tests were run with all three faults combined (`FAULTS=all`).

---

## Soak Test Results

**6-hour soak test** — `WORKLOAD=set FAULTS=all TIME_LIMIT=21600` — passed on 2026-05-14:

```
:valid?           true
:stable-count     49
:never-read-count 14
:lost-count       0
:duplicated-count 0
```

All 63 attempted elements were either stably confirmed present or never read after the cluster recovered — no element was lost or duplicated.

**Short tests (120s)** — all five workloads under `FAULTS=kill,partition` and `FAULTS=all`:

| Workload   | `FAULTS=kill,partition` | `FAULTS=all` |
| ---------- | ----------------------- | ------------ |
| `register` | ✅ PASS                 | ✅ PASS      |
| `bank`     | ✅ PASS                 | ✅ PASS      |
| `set`      | ✅ PASS                 | ✅ PASS      |
| `append`   | ✅ PASS                 | ✅ PASS      |
| `watch`    | ✅ PASS                 | ✅ PASS      |

---

## What Is Not Guaranteed

The following properties are **not** covered by this test suite:

| Property                              | Reason not tested                                                                                                                            |
| ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| Distributed lock correctness          | No distributed lock workload in the test suite; CAS primitives exist but lock lifecycle (acquire/renew/release under faults) is not verified |
| Dynamic cluster membership            | d-engine supports dynamic node expansion; no Jepsen workload yet exercises membership changes under fault injection                          |
| Bounded recovery time                 | Tests confirm recovery occurs; they do not measure or bound how long it takes                                                                |

---

## Test Environment

- d-engine version: v0.2.4
- Jepsen version: 0.3.5
- Cluster: 3 nodes (Docker containers, single host)
- Checker: Knossos (linearizability), Elle (strict-serializable), custom (watch ordering), balance invariant, set-full
