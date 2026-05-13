# Changelog

All notable changes to d-engine-jepsen are documented here.
Versions track d-engine releases — e.g. `v0.2.4` tests d-engine `v0.2.4`.

---

## [0.2.4] — 2026-05-13

### Changed

- **`append` workload: upgrade consistency model to `:strict-serializable`** (`src/jepsen/d_engine/append.clj`)  
  Changed `:consistency-models [:sequential]` → `[:strict-serializable]`. Sequential consistency
  only verifies per-process ordering; strict serializability matches d-engine's actual Raft
  guarantee (linearizability) and catches stale reads — a client seeing outdated data after
  another client's write has already been committed and acknowledged.  
  Also fixed docstring: encoding range is values 1–255 (not 1–127).

### Fixed

- **`db/start!` hang** (`src/jepsen/d_engine/db.clj`)  
  Replaced manual `bash -c '... &'` with `cu/start-daemon!` (`start-stop-daemon --background`).  
  Root cause: `c/su` → `sudo` maintains a process session and waits for all descendants to exit.
  A long-running backgrounded `demo` process caused `sudo` to never release the session,
  so SSHJ's `c/exec` blocked indefinitely on every `:start :all` nemesis operation.
  `start-stop-daemon --background` performs a kernel-level double-fork that fully escapes
  the sudo session; the helper exits immediately and SSHJ returns normally.

- **`set` workload: `:valid? :unknown` result** (`src/jepsen/d_engine/set.clj`)  
  Changed add-value generation from `rand-int 30` to a unique sequential sequence `(range 63)`.  
  Root cause: `set-full` checker resets an element's tracking state on every new `:invoke :add V`.
  With repeated values and a cluster outage near the end of the test, all 30 elements had their
  state reset after the last successful read, causing `:stable-count 0` → `:valid? :unknown`.

- **`append` workload: Elle `AssertionError` crash** (`src/jepsen/d_engine/append.clj`)  
  Added 8-bit overflow guard: when `next-val > 255`, `append-op` emits a read instead.  
  Root cause: the global `next-val` counter grew to ~600 in a 120s test. Values ≥ 256 corrupted
  the packed u64 encoding — `encode-append` of v=276 spread bits across adjacent slots, so
  `decode` returned phantom values (e.g. 276 → [20, 1]) that no transaction ever wrote.
  Elle's `dirty_update_cases` detected this as an impossible history and threw an `AssertionError`.

### Added

- **Final generator phase** (`src/jepsen/d_engine.clj`)  
  After `TIME_LIMIT` expires the test now runs: stop all faults → sleep 10s → final client read.
  Matches the etcd Jepsen reference pattern. Ensures the checker sees a confirmed read after
  the cluster has recovered, allowing `set-full` to report `:stable` rather than `:never-read`.

- **`set` workload `:final-generator`** (`src/jepsen/d_engine/set.clj`)  
  Added `(gen/once read-op)` as the workload's final generator, used by the final phase above.

### Verified

All four workloads pass under `FAULTS=kill,partition` and `FAULTS=all` with `TIME_LIMIT=120`:

| Workload   | `FAULTS=kill,partition` | `FAULTS=all` |
| ---------- | ----------------------- | ------------ |
| `register` | ✅ PASS                  | ✅ PASS       |
| `bank`     | ✅ PASS                  | ✅ PASS       |
| `set`      | ✅ PASS                  | ✅ PASS       |
| `append`   | ✅ PASS                  | ✅ PASS       |

---

## [0.2.3] and earlier

See git log for history prior to v0.2.4.
