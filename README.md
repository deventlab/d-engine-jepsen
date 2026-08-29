# Jepsen Tests for d-engine

[Jepsen](https://jepsen.io/) tests for validating linearizability of d-engine under network partitions and node failures.

## Test Coverage

### Current Scope

d-engine-jepsen validates the following correctness properties:

#### ✅ Tested Scenarios

- **Linearizability**: Single-key read/write operations maintain strict ordering
- **Network partitions**: Cluster recovers correctly after majority/minority splits
- **Node failures**: Leader election and log replication after crash/restart
- **Process suspension**: System handles slow nodes (SIGSTOP/SIGCONT)
- **Concurrent operations**: Multiple clients writing to independent keys
- **Snapshot installation**: Lagged followers recover via snapshot after log compaction
- **Snapshot transfer**: Leader sends snapshot to minority nodes after kill/restart
- **Write conflict detection**: CAS-based append operations checked for ordering anomalies (Elle)
- **Dynamic membership**: Learners join and promote to Voters without violating stream invariants
- **Quorum durability (experimental)**: Whether an entry reported as committed can be lost if the leader and the follower that completed its quorum crash together before syncing to disk — see [`make test-durability`](#durability-campaign-experimental)

### Workloads

| Workload     | Checker                    | Description                              |
| ------------ | --------------------------- | ---------------------------------------- |
| `register`   | Linearizable (Knossos)      | Single-key read/write                    |
| `bank`       | Balance invariant           | Concurrent transfers across accounts     |
| `set`        | Set membership              | Concurrent add/read                      |
| `append`     | Elle (strict-serializable)  | List-append with ordering anomaly detection |
| `watch`      | Custom (order + phantom)    | Watch stream delivers events in commit order |
| `scan-watch` | Custom (no-gap/no-phantom)  | Scan-then-watch reconnection: no gap or duplicate between snapshot and stream |
| `membership` | Custom (stream invariants)  | Dynamic node join: node4/5 start as Learners and auto-promote to Voters; `watch_membership` streams verified for monotone committed-index, no member/learner overlap, and non-empty members |

### What This Test Suite Guarantees

See [GUARANTEES.md](./GUARANTEES.md) for the full list of Jepsen-verified correctness properties and soak test results.

## Prerequisites

- Docker & Docker Compose

No other setup is required to run against a released d-engine version — the
node and controller images are pre-built and published to Docker Hub.

## Build Docker Images

Two options, depending on what you want to test:

### Option A — test a released d-engine version (default)

`docker-compose.yml` references pre-built images published to Docker Hub
(`deventlab/d-engine:<version>`, `deventlab/d-engine-jepsen:<version>`).
Nothing to build — `docker compose up` pulls them directly.

### Option B — test your own d-engine checkout

To run against local d-engine source (a branch, an unreleased fix, current
`main`) instead of a published tag:

1. Clone `d-engine` as a sibling directory to this repo:
   ```bash
   git clone https://github.com/DEventLab/d-engine.git ../d-engine
   ```
2. Copy the local-build override template and build from it:
   ```bash
   cp docker-compose.override.yml.example docker-compose.override.yml
   docker compose build
   ```
   `docker-compose.override.yml` is gitignored — it's meant to point at
   whatever local d-engine checkout you're testing, not to be shared.

## Quick Start

```bash
# Run tests
make test

# View results
make view
```

## Architecture

```
┌──────────────────────┐
│ d-engine-jepsen       │  Control node - runs Jepsen test
└──────────┬────────────┘
           │ SSH
      ┌────┴────┬────────┐
      ▼         ▼        ▼
  ┌───────┐ ┌───────┐ ┌───────┐
  │ node1 │ │ node2 │ │ node3 │  d-engine (Raft cluster)
  └───────┘ └───────┘ └───────┘
      │         │        │
      └────┬────┴────────┘
           ▼
     d-engine cluster (Raft)
```

## Usage

### Run Tests

```bash
# Default (register workload, partition fault, 60s)
make test

# Full parameter example
make run-workload WORKLOAD=set FAULTS=kill,partition TIME_LIMIT=120 RATE=20
```

### Test Parameters

| Parameter          | Default      | Options / Description |
| ------------------ | ------------ | --------------------- |
| `WORKLOAD`         | `register`   | `register` `bank` `set` `append` `watch` `scan-watch` `membership` — which correctness property to test (see [Workloads](#workloads)) |
| `FAULTS`           | `partition`  | `partition` `kill` `pause` `all` (comma-separated) — which failure modes to inject |
| `TIME_LIMIT`       | `60`         | Test duration in seconds. 120 is a reasonable default; use 300+ for soak tests |
| `RATE`             | `10`         | Target client operations per second. Higher values increase concurrency stress |
| `NEMESIS_INTERVAL` | `10`         | Seconds between nemesis actions. Lower = faults arrive more frequently |
| `LAZYFS`           | (unset)      | Set to `1` to mount node data dirs under [lazyfs](https://github.com/dsrhaslab/lazyfs) — makes `kill` also drop writes never fsync'd (simulated power loss). See [Durability campaign](#durability-campaign-experimental) |

**`FAULTS` — how to break the cluster:**

| Value       | What it does |
| ----------- | ------------ |
| `partition` | iptables network partition (majority / minority / primaries) |
| `kill`      | SIGKILL the demo process on a minority of nodes — or, if `LAZYFS=1`, the current leader + one other node (the exact pair a quorum-durability violation requires) |
| `pause`     | SIGSTOP / SIGCONT (simulates a slow/frozen node) |
| `all`       | All three combined |

### Convenience Targets

```bash
make test-scan-watch                              # scan-then-watch reconnection workload
make test-membership                               # node4/5 join as Learners, promote to Voters
make test-membership-readonly                      # node4/5 join as ReadOnly, must never promote
make test-membership-single TIME_LIMIT=420         # single-learner membership mode
```

### Durability campaign (experimental)

```bash
make test-durability                       # append/Elle + lazyfs + leader-plus-one kill, rate=200, 300s
make test-durability RATE=500 TIME_LIMIT=600
```

Not part of `make test` and not a pass/fail CI gate. Catching a
quorum-before-durable-persist violation needs sustained write pressure so an
unflushed backlog actually exists when the kill lands — treat this as a
statistical campaign (run it repeatedly / for longer), not a single-run
verdict. Background and rationale in
`../deventlab-product-design/d-engine/tickets/milestones/v0.2.5/444-445-jepsen-lazyfs-methodology-gap.md`.

### Other Commands

```bash
make view          # Open linearizability report in browser
make report        # Print path to latest report
make clean         # Remove test artifacts
make restart-stack # Restart Docker containers
make ssh-setup     # Re-add the SSH key inside the Jepsen controller container
```

## Output

- Test results: `./store/latest/`
- Linearizability report: `./store/latest/independent/0/linear/linear.html`

## SSH Keys

The `sshkeys/` directory contains test-only SSH keys for Jepsen to control node containers. These are NOT production keys.

## Manual Testing

```bash
docker exec -it d-engine-jepsen-jepsen-1 bash
eval $(ssh-agent -s) && ssh-add /root/.ssh/id_rsa
lein run test --node node1 --node node2 --node node3 \
  --endpoints http://node1:9081,http://node2:9082,http://node3:9083 \
  --time-limit 60 --workload register --faults kill
```
