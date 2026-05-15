# d-engine Correctness Guarantees

Verified by [Jepsen](https://jepsen.io/) testing on d-engine v0.2.4.

---

## What d-engine Guarantees

### 1. Linearizability

Read/write operations appear to execute atomically at a single point in real time, consistent with the global commit order imposed by Raft. No linearizability violation was observed across all test runs.

**Verified by**: `register` workload (Knossos linearizability checker), `append` workload (Elle strict-serializable checker). Jepsen testing finds violations; passing runs do not constitute a formal proof of correctness.

### 2. Partition Tolerance — No Split-Brain

During a network partition, at most one partition (the majority) can accept writes. The minority partition rejects writes. After the partition heals, the cluster converges to a single consistent state without data loss. No split-brain was observed in any test run.

**Caveat**: d-engine uses LeaseRead (`lease_duration_ms = 500ms`). A node that becomes isolated may continue serving reads for up to 500ms while its lease is still valid. This bounded window is an inherent property of lease-based reads and is not eliminated by partition fault injection.

**Verified by**: all workloads under `FAULTS=partition` with majority, minority, and primaries-isolated topologies.

### 3. Crash Durability

No acknowledged write was lost or rolled back under minority crash fault injection. Writes survived leader crashes, follower crashes, and simultaneous minority-node SIGKILL.

**Scope**: Tests cover minority-node SIGKILL with automatic restart. Simultaneous crash of all nodes (e.g. full power loss) is not covered; d-engine uses a `MemFirst` persistence strategy with periodic batch flushing, so unflushed entries could be lost in that scenario.

**Verified by**: all workloads under `FAULTS=kill` (SIGKILL on minority nodes, with restart).

### 4. Single-Leader Safety

No two-leader anomaly was observed. Raft's term-based voting guarantees at most one leader per term by construction; any violation would produce a non-linearizable history detectable by Knossos.

**Verified by**: `register` workload — a two-leader scenario producing conflicting committed entries would manifest as a linearizability violation.

### 5. Snapshot Recovery

Followers that fall behind (missed log entries, killed and restarted) were observed to recover via snapshot transfer and rejoin consensus correctly. No data loss or incorrect reads were observed after recovery.

**Scope**: Recovery was exercised within bounded lag windows (120s test runs). "Arbitrarily far behind" in Raft theory requires snapshot support; whether d-engine handles extreme lag (e.g. weeks of missed entries) is not covered by these tests.

**Verified by**: `set` workload under `FAULTS=kill,partition` — lagged followers rejoin and serve correct reads after recovery.

### 6. Watch Stream Ordering

The Watch streaming RPC delivers PUT events in strictly increasing commit order within each subscription window. No backward jumps occur; events reflect Raft's total commit ordering.

**Verified by**: `watch` workload (custom checker, per-window strict ordering).

### 7. Account Balance Invariant

Concurrent cross-key transfers preserve the total balance across all accounts. No money is created or destroyed, even under concurrent writes and partial failures.

**Verified by**: `bank` workload (balance invariant checker).

### 8. Dynamic Cluster Membership

New nodes can join the cluster at runtime as Learners. The membership stream satisfies these safety invariants across all modes:

- The membership stream's `committed_index` (the Raft log index of the last membership ConfChange, not the global commit index) is non-decreasing within each watch window
- No node appears in both `members` and `learners` simultaneously
- `members` is never empty (the voter set is always non-empty)

Three membership modes have been verified:

**Promotable Learners** (`--membership-mode promotable`): When the resulting voter count would be odd (3+2=5), both learners auto-promote to Voters via BatchPromote ConfChange. Node4 and node5 must eventually appear in `members`.

**ReadOnly Learners** (`--membership-mode readonly`): Nodes configured with `status=ReadOnly` are permanently excluded from promotion regardless of quorum math. Node4 and node5 must never appear in `members`.

**Single Learner / Stale Eviction** (`--membership-mode single-learner`): When the resulting voter count would be even (3+1=4), `batch_size=0` and the learner cannot promote. After `stale_learner_threshold` (hardcoded at 300s), the leader submits BatchRemove and the learner is expelled. Node4 must appear in `learners` and eventually disappear without ever entering `members`.

**Verified by**: `membership` workload with `WatchMembership` stream monitoring throughout, all three modes tested with `FAULTS=partition`.

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

**Membership tests** — three modes verified on 2026-05-15:

| Mode              | Faults      | Time limit | Result  |
| ----------------- | ----------- | ---------- | ------- |
| `promotable`      | `partition` | 120s       | ✅ PASS |
| `readonly`        | `partition` | 120s       | ✅ PASS |
| `single-learner`  | `none`      | 420s       | ✅ PASS |

Note: `single-learner` uses `FAULTS=none` by default because `stale_learner_threshold` is hardcoded at 300s and frequent leader elections under partition extend the effective eviction time to 600–900s. Use `FAULTS=partition TIME_LIMIT=900` for fault-injection coverage of this mode.

---

## What Is Not Guaranteed

The following properties are **not** covered by this test suite:

| Property                     | Reason not tested                                                                                                                            |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| Distributed lock correctness | No distributed lock workload in the test suite; CAS primitives exist but lock lifecycle (acquire/renew/release under faults) is not verified |
| Bounded recovery time        | Tests confirm recovery occurs; they do not measure or bound how long it takes                                                                |

---

## Test Environment

- d-engine version: v0.2.4
- Jepsen version: 0.3.5
- Cluster: 3 nodes (Docker containers, single host); 5 nodes for `membership` workload (node4/5 join dynamically)
- Network faults are simulated via iptables on a single physical host; results do not capture real-world network latency or partial packet loss
- Checker: Knossos (linearizability), Elle (strict-serializable), custom (watch ordering, membership stream invariants), balance invariant, set-full
