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

### Workloads

| Workload   | Checker                  | Description                              |
| ---------- | ------------------------ | ---------------------------------------- |
| `register` | Linearizable (Knossos)   | Single-key read/write                    |
| `bank`     | Balance invariant        | Concurrent transfers across accounts     |
| `set`      | Set membership           | Concurrent add/read                      |
| `append`   | Elle (strict-serial)     | List-append with ordering anomaly detection |
| `watch`    | Custom (order + phantom) | Watch stream delivers events in commit order |

### What This Test Suite Guarantees

If tests pass, d-engine provides:

1. ✅ Linearizable reads/writes - operations appear atomic and real-time ordered
2. ✅ Partition tolerance - no split-brain during network failures
3. ✅ Durability - committed writes survive leader crashes
4. ✅ Single-leader safety - only one leader per term
5. ✅ Snapshot recovery - restarted nodes catch up correctly via snapshot transfer

This test suite does NOT guarantee:

1. ❌ Distributed lock correctness (requires CAS operations)
2. ❌ Dynamic cluster stability (requires membership testing)

## Prerequisites

- Docker & Docker Compose
- `.env` file with `S3_BASE_URL` configured (see [Configuration](#configuration))

## Configuration

1. Copy `.env.example` to `.env`:

```bash
cp .env.example .env
```

2. Edit `.env` and set `S3_BASE_URL` to your d-engine binary repository:

```bash
# .env
S3_BASE_URL=https://your-s3-bucket.example.com
```

Replace `https://your-s3-bucket.example.com` with your actual S3 bucket or internal mirror URL.

3. Configure binary names for your platform (optional):

```bash
# For Linux (default)
D_ENGINE_BINARY=three-nodes-embedded-linux-amd64
D_ENGINE_CTL_BINARY=dengine_ctl-linux-amd64

# For macOS ARM64
D_ENGINE_BINARY=three-nodes-embedded-darwin-arm64
D_ENGINE_CTL_BINARY=dengine_ctl-darwin-arm64
```

**Important:** `.env` is in `.gitignore` and should never be committed.

## Binaries

This test suite requires two d-engine binaries:

1. **three-nodes-embedded** - d-engine standalone cluster binary
   - Source: [examples/three-nodes-embedded](https://github.com/DEventLab/d-engine/tree/main/examples/three-nodes-embedded)
   - Version: d-engine v0.2.4
   - Purpose: Runs a 3-node Raft cluster for testing

2. **dengine_ctl** - d-engine client CLI tool
   - Source: [examples/client-usage-standalone](https://github.com/DEventLab/d-engine/tree/main/examples/client-usage-standalone)
   - Version: d-engine v0.2.4
   - Purpose: Client to interact with the cluster (put/get operations)

Both binaries are automatically downloaded from S3 during test setup. The download URLs are configured via `S3_BASE_URL` in `.env`.

## Build Docker Images

```bash
# Build both Jepsen control node and d-engine cluster nodes
docker compose build

# Or build individually
docker build -t jepsen:2.0 -f Dockerfile .
docker build -t jepsen-node:2.0 -f Dockerfile.node .
```

## Quick Start

```bash
# Run tests (first run downloads binaries from S3)
make test

# View results
make view
```

## Architecture

```
┌─────────────────┐
│  jepsen:2.0     │  Control node - runs Jepsen test
└────────┬────────┘
         │ SSH
    ┌────┴────┬────────┐
    ▼         ▼        ▼
┌───────┐ ┌───────┐ ┌───────┐
│ node1 │ │ node2 │ │ node3 │  jepsen-node:2.0
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
make test WORKLOAD=set FAULTS=kill,partition TIME_LIMIT=120 RATE=20
```

### Test Parameters

| Parameter          | Default      | Options / Description |
| ------------------ | ------------ | --------------------- |
| `WORKLOAD`         | `register`   | `register` `bank` `set` `append` — which correctness property to test (see [Workloads](#workloads)) |
| `FAULTS`           | `partition`  | `partition` `kill` `pause` `all` (comma-separated) — which failure modes to inject |
| `TIME_LIMIT`       | `60`         | Test duration in seconds. 120 is a reasonable default; use 300+ for soak tests |
| `RATE`             | `10`         | Target client operations per second. Higher values increase concurrency stress |
| `NEMESIS_INTERVAL` | `10`         | Seconds between nemesis actions. Lower = faults arrive more frequently |

**`WORKLOAD` — what to test:**

| Value      | Tests what?                             | Checker |
| ---------- | --------------------------------------- | ------- |
| `register` | Single-key read/write is linearizable   | Knossos |
| `bank`     | Transfers never lose or create money    | balance invariant |
| `set`      | Every acknowledged add survives faults  | set-full |
| `append`   | List-append has no ordering anomalies   | Elle |
| `watch`    | Watch stream delivers events in commit order, no phantoms | custom |

**`FAULTS` — how to break the cluster:**

| Value       | What it does |
| ----------- | ------------ |
| `partition` | iptables network partition (majority / minority / primaries) |
| `kill`      | SIGKILL the demo process on a minority of nodes |
| `pause`     | SIGSTOP / SIGCONT (simulates a slow/frozen node) |
| `all`       | All three combined |

### Convenience Targets

```bash
# Run all four workloads sequentially (stops on first failure)
make test-all FAULTS=kill,partition TIME_LIMIT=120

# High-concurrency stress test (rate=200, 10 min)
make test-stress WORKLOAD=set

# Combined faults, moderate rate, 5 min
make test-combined WORKLOAD=bank
```

### Other Commands

```bash
make view          # Open linearizability report in browser
make report        # Print path to latest report
make clean         # Remove test artifacts
make restart-stack # Restart Docker containers
```

## Output

- Test results: `./store/latest/`
- Linearizability report: `./store/latest/independent/0/linear/linear.html`

## SSH Keys

The `sshkeys/` directory contains test-only SSH keys for Jepsen to control node containers. These are NOT production keys.

## Manual Testing

```bash
docker exec -it d-engine-jepsen-jepsen-1 bash
lein run test --node node1 --node node2 --node node3 \
  --ssh-private-key /root/.ssh/id_rsa --time-limit 60
```
