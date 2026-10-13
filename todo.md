# Kaflow V1 tickets and Codex working paper

## Board

Status: READY, IN_PROGRESS, BLOCKED, DONE. One active ticket maximum.
Last updated: 2026-10-13. Current ticket: **none**. Latest completed: **KF-002**.

| Ticket | Scope | Status | Dependencies |
| --- | --- | --- | --- |
| KF-001 | Reproducible source build and honest baseline | DONE | None |
| KF-002 | Injected broker ownership and V1 record/API contracts | DONE | KF-001 |
| KF-003 | Partition-log framing, indexes, rotation, bounded immediate reads | READY | KF-002 |
| KF-004 | Durable publication and crash recovery | READY | KF-003 |
| KF-005 | Leased groups and generation fencing | READY | KF-004 |
| KF-006 | Durable commits, immediate Fetch API, independent replay | READY | KF-005 |
| KF-007 | Asynchronous long polling and cancellation | READY | KF-006 |
| KF-008 | Retention, deletion, overload, bounded shutdown | READY | KF-007 |
| KF-009 | Operable release, Docker, metrics, measured benchmarks | READY | KF-008 |

READY means scoped backlog, not permission to bypass unfinished dependencies.
Split large tickets into numbered child tickets before implementation when necessary.

## Accepted direction

Single-node durable partitioned log; Java 17; REST administration; custom `kaflow.v1`
gRPC operations Append, Fetch, Commit, Join, Heartbeat, Leave. Fixed partitions,
one-topic groups, opaque bytes, explicit/key/unkeyed routing, actual append offsets,
strict durability, Linux local storage reference, macOS development supported.
No prototype API compatibility or volatile-state migration is required.

Coordinator owns membership, leases, lifecycle, admission, waiters, and published
boundaries. A storage writer performs disk mutations; a bounded pool performs short
reads. Storage stalls latch degraded readiness; keep diagnostic/control traffic
responsive and defer unsafe reassignment until recovery. Use monotonic lease time.
Commits are monotonic/idempotent next-offset updates with ownership validation and
a commit/rebalance barrier. Waiting fetches release read workers.

Before KF-004 settle checkpoint representation/cost, crash states, segment rotation,
directory sync, and late completion after a watchdog timeout. Recovery must fail
closed on damage within retained published data. Basic bounded reads precede long
polling. Replication, transactions, exactly-once, compaction, UI, and custom crypto
are outside V1. Historical notes: `docs/history/prototype-todo.md`.

## KF-001 — Reproducible source build and honest baseline

**Status:** DONE (2026-10-13).
**Working branch:** `codex/kf-001-source-build`.
**Publication:** User authorized commit and push on 2026-10-13; no PR requested.
**Base:** `a3b9026a8c39fd7b8edd47992467d576eb72ee62`.
**Detailed evidence:** [docs/baseline/kf-001.md](docs/baseline/kf-001.md).

### Scope and exclusions

Source-only generation, compilation, packaging, existing-test reporting, packaged
startup/shutdown, development instructions, and Linux CI. Production behaviour,
assertions, source, schema, wrapper, dependencies, and active Gradle configuration
are unchanged. No architecture, persistence, V1 RPC, subscription, type, routing,
offset, framework-upgrade, or historical-test reactivation work was performed.

### Acceptance

- [x] Record this checkout's environment and disposable original build result.
- [x] Stop tracking confirmed outputs; preserve real configuration and schema/wrapper.
- [x] Build/package a clean source-only snapshot containing the uncommitted changes.
- [x] Record discovered/executed/passed/failed/skipped/error test counts accurately.
- [x] Record packaged loopback startup, HTTP/gRPC listeners, health, and shutdown.
- [x] Add development instructions and Linux CI with failure-preserving report uploads.
- [x] Review diff, deferred defects, blockers, and exact handoff.

### Changed files and reasons

- `.gitignore`: ignore all generated/build output, `app/bin/`, and compiled classes;
  remove the old generated-source exception.
- `app/build/**` and `app/bin/**`: remove 97 entries from Git tracking (76 classes,
  10 generated Java files, 5 Finder metadata files, 2 placeholders, 4 configurations).
  Local files remain ignored. The four configurations were preserved verbatim under
  `docs/history/editor-build/`, not discarded as generated output.
- `docs/history/prototype-todo.md`: original `Todo.md` from committed base, verbatim.
- `docs/history/imported-baseline-working-paper.md`: supplied board before this work,
  verbatim. It describes a separate Linux workspace; its prepared implementation was
  absent here. `docs/history/README.md` records provenance and archived config paths.
- `todo.md`: canonical lowercase Git path, updated ticket working paper; accepted
  direction and remaining ticket outlines retained. No second case-only board.
- `docs/development.md`: Java 17/macOS/Linux prerequisites, exact wrapper commands,
  report paths, timeouts, source-only validation, and repeatable smoke procedure.
- `scripts/smoke_bootjar.py`: standard-library, bounded executable-JAR startup/check/
  SIGTERM cleanup, selected loopback ports, retained logs and JSON outcomes.
- `.github/workflows/build.yml`: Ubuntu 24.04, Temurin 17, pinned actions, wrapper
  build/test/smoke, 25-minute job limit, 10-minute build/test limits, reports uploaded
  with `if: always()`. No `continue-on-error` or failure-masking shell commands.
- `README.md`: prominent link to current development/baseline docs and historical
  claim qualification. `docs/baseline/kf-001.md`: durable record of observed evidence.
- Pre-existing user `AGENTS.md` retained byte-for-byte and included in the commit
  as the repository working agreement. No unrelated work discarded.

### Environment, commands, and results

This checkout started on `main` at the base above, with modified historical index
path `Todo.md` (on disk `todo.md`) and untracked `AGENTS.md`. The imported Linux
board was not evidence of this macOS environment or completed local changes.

Actual environment: macOS 26.6.2 (25G83), arm64, Eclipse Temurin 17.0.20.1+1,
Gradle wrapper 8.5. Java was initially missing. Installed a checksum-verified
vendor archive only in the temporary run directory, without changing system defaults:

```sh
export JAVA_HOME='/private/tmp/kaflow-kf001-f0goax2f/jdk-17.0.20.1+1/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME='/private/tmp/kaflow-kf001-f0goax2f/gradle-home'
```

1. Original committed snapshot: `/private/tmp/kaflow-kf001-f0goax2f/original`, from
   `git archive HEAD`, intentionally excluding the two pre-existing documentation
   edits. Ran `./gradlew :app:clean :app:build --no-daemon` before untracking outputs.
   First attempt exited 1 in 0.99 seconds at wrapper download (Java TLS trust failure);
   no compilation, packaging, or tests ran. Added the already-trusted macOS Cisco CA
   only to the temporary JDK trust store, with TLS verification still enabled.
   Retry exited 0 in 131.71 seconds: generation, compilation, packaging, 7/7 tests.
2. Candidate snapshot: `/private/tmp/kaflow-kf001-f0goax2f/candidate`; copied 125 current
   tracked/intended untracked files including uncommitted edits and executable modes,
   excluding `.git`, `.gradle`, all build/bin outputs and classes. Reused downloaded
   tooling/dependencies, not project outputs or a build cache. Manifest retained.
   Ran `./gradlew clean :app:classes :app:testClasses :app:bootJar --no-daemon`:
   exit 0 in 5.48 seconds, generated 10 Java files, compiled main/tests, packaged
   `app/build/libs/app.jar`; verified generated model/gRPC classes inside the JAR.
3. Separate `./gradlew :app:test --rerun-tasks --no-daemon`: exit 0 in 20.35 seconds.
   Fresh XML: discovered/executed/passed **7/7/7**, failed **0**, skipped **0**, errors
   **0**. `SimpleOffsetTest` 2, `OffsetManagementTest` 2, stream integration 3.
   No discovery/setup/test-body/teardown failure or task timeout observed. Six old
   commented files remain inactive. No coverage measurement or historical 75+ claim.
4. `python3 scripts/smoke_bootjar.py` against the actual candidate JAR: exit 0.
   HTTP `127.0.0.1:53023`, gRPC `127.0.0.1:53024`. Health 200/UP, admin create 201,
   admin read 200; gRPC registered/listening. SIGTERM exited 143 in 0.453 seconds,
   both listeners closed; no timeout or forced kill. Startup evidence only.
5. `./gradlew --version --no-daemon`: exit 0; confirms Gradle 8.5 and actual Java 17.
   Gradle runs bounded at 600 seconds; smoke bounded at 60/20 seconds startup/shutdown.
6. Local review: whitespace checks and Python syntax passed; workflow YAML parsed;
   failure propagation, timeout and always-upload verified. Archived content, supplied
   AGENTS, production/test sources, dependencies/build files, schema and wrapper
   preserved byte-for-byte. No tracked app/build or app/bin entries remain.

Raw local evidence: ignored `build/kf001-evidence/` contains command logs/exit timing,
manifest, original/candidate JUnit XML and HTML, and smoke logs/result JSON. Normal
report paths are in the development guide. The detailed record preserves essentials
if temporary files or ignored reports are removed. Final documentation updates came
after the candidate run; built inputs, smoke script, and CI match that snapshot.

### Blockers and deferred work

No remaining blocker for KF-001's local acceptance. Missing Java and the managed
network's Java trust issue were resolved locally; neither is a source-build defect.
Linux CI was authored and checked locally; no GitHub Actions result was available
at local validation. Commit and push are now authorized. Verify the run for the
published commit before reporting a passing Linux build.

No behavioural failure was exposed by these runs, so no new defect ticket was
opened. Existing unimplemented guarantees remain explicitly in KF-002 (ownership,
record contracts/routing, test isolation), KF-006 (Fetch/Commit), KF-007 (waiting and
cancellation), and KF-008 (shutdown under load/resource bounds). Passing seven tests
or a health check does not establish those guarantees. No dependent work started.

### Exact next action

After the authorized commit/push, inspect the first Linux workflow run for
`codex/kf-001-source-build` and its retained reports; do not claim CI success before
verifying its result. If rerunning locally, use the environment above (or
another JDK 17 if the temporary installation is gone) and commands in the development
guide. KF-002 is the next eligible ticket but was not started by this request.

## KF-002 — Isolated ownership, byte records, and Append contracts

**Status:** DONE (2026-10-13).
**Publication:** User authorized commit and push on 2026-10-13; no PR requested.
**Branch:** `codex/kf-002-broker-contracts`.
**Base:** `a704b7067710c776872c4ba60a453fba3308da52` (clean merged KF-001 tree).
**Design:** [docs/design/kf-002-broker-contracts.md](docs/design/kf-002-broker-contracts.md).
**Detailed evidence:** [docs/baseline/kf-002.md](docs/baseline/kf-002.md).

### Scope and decisions

Inject isolated prototype state through controllers/helpers/factories and composition
roots. Add a separate in-memory V1 core and single-record unary Append schema.
Opaque immutable payload/key bytes; fixed qualified partitions; deterministic routing;
truthful offsets/results; bounded admission/retained accounting; ordered close and
bounded package-private inspection. Preserve existing prototype assertions and behaviour.
No registered V1 implementation, successful V1 network acknowledgements, persistence,
Fetch/Commit, leases, subscriptions, retention, migration, or KF-003 work. No automatic
commit, push, or PR. The approved design document defines precise defaults and semantics.

### Acceptance

- [x] Remove prototype singleton/static factory state; prove independent ownership.
- [x] Preserve seven existing assertions/tests with isolated fixtures and ephemeral ports.
- [x] Immutable opaque bytes, exact binary/empty round trips, fixed qualified identities.
- [x] Concrete unary V1 schema; no registered service or successful network Append.
- [x] Actual partition/offset/timestamp receipts and independent fixed routing vectors.
- [x] Contiguous unique concurrent offsets; exact stored/result correspondence; sequential order.
- [x] Independent count/byte limits, including zero-byte records/calls and failure release.
- [x] Deterministic close races, idempotence, memory-reference/accounting release, closed operations.
- [x] Bounded immutable package-private snapshots, including after-close inspection rules.
- [x] Clean source-only compile/package, separate full tests, packaged smoke, reviewed diff.
- [x] Record files, commands/results, blockers, deferred limitations, and exact handoff.

### Evidence and progress

- Initial checkout clean on `main` at the base above; no user edits to preserve.
- Independently verified KF-001 [Linux run 38364209464](https://github.com/Biswas57/kaflow/actions/runs/38364209464):
  revision `7b3f527f579b86042a0e6b17df80e3c18b2ca7ed`; build/package, tests,
  packaged smoke, and report upload all succeeded. This does not claim execution
  against the later merge revision or KF-002.
- Prototype: injected cluster/controller/helper/factories, Spring-owned service cleanup,
  explicit standalone/CLI cleanup, ephemeral test ports and bounded channel/server teardown.
- New core: `app/src/main/java/kaflow/broker/` owns immutable values, qualified records,
  routing/offsets, admission/retained limits, close and package-private bounded inspection.
  New schema: `app/src/main/proto/kaflow/v1/broker.proto`; generated, never registered.
- Tests: `kaflow.broker` contracts/capacity/concurrency; `kaflow.v1` schema;
  prototype ownership, real Spring composition and both V1 network rejection paths.
  All seven original tests and 31 original assertion/failure lines remain preserved.
- Development full test run exited 0 in 10.60 s: 38 passed, zero failures/errors/skips.
  Review added lock-wait admission and per-topic round-robin tests, strengthened close
  ordering checks, and completed composition-root cleanup before the final snapshot.
- Clean source-only candidate copied 145 current tracked/intended untracked files
  (73 Java, two schemas) with hash/mode manifest, no project outputs or compiled classes:
  `/private/tmp/kaflow-kf002-anlnt21x/candidate`. Reused Java 17/dependency downloads only.
- `./gradlew clean :app:classes :app:testClasses :app:bootJar --no-daemon`: exit 0,
  5.33 s; 18 generated Java files, production/tests compiled, executable JAR packaged.
- Separate `./gradlew :app:test --rerun-tasks --no-daemon`: exit 0, 8.61 s;
  **40 discovered/executed/passed**, **0 failed**, **0 errors**, **0 skipped**.
  No setup/teardown/task timeouts. Both Gradle invocations bounded at 600 s.
- `python3 scripts/smoke_bootjar.py`: exit 0. HTTP/gRPC ports 57156/57157,
  health 200/UP, admin create/read 201/200, SIGTERM exit 143 in 0.476 s,
  both listeners closed, no error/timeout/forced kill. Startup evidence, not durability.
- Real standalone and Spring V1 Append calls returned UNIMPLEMENTED. Spring REST
  creation and legacy gRPC production shared injected state. Concurrent core tests
  matched all 800 accepted appends to unique contiguous partition records.
- Diff/scope review and whitespace checks passed. Immutable payload boundary, fixed
  vectors, each limit, exception release, both close outcomes and bounded inspection
  have focused passing evidence. No production/test/schema changes after the candidate.
- Raw evidence retained in ignored `build/kf002-evidence/`; durable details and report
  paths in `docs/baseline/kf-002.md`. No failed attempts or remaining blocker. No KF-002
  Linux CI run claimed, and no durability, Fetch or producer-sequencing claim.

### Exact next action

Commit and push this reviewed, validated change as authorized. Verify the resulting
Linux workflow before recording CI success; no PR requested.
KF-003 is the next eligible ticket, but is not started; expand its storage/framing
acceptance criteria only on the next implementation request. Keep V1 unregistered
until KF-004 durability. Built inputs match the retained source manifest; final
source-of-truth updates are documentation only.

## Remaining ticket acceptance outlines

Expand the next eligible ticket into scope, decisions, steps, evidence, and handoff.

- **KF-003:** versioned/checksummed framing, short writes, bounded immediate reads,
  sparse rebuildable indexes, rotation, exclusive data-directory ownership. No durability
  claim until KF-004. Demonstrate append/read at storage level.
- **KF-004:** crash-state and bounded-checkpoint decisions; late completion policy;
  durable publication; restart/failpoint/corruption/disk-full evidence; unknown retry
  outcomes documented; filesystem assumptions enforced.
- **KF-005:** durable group definitions, leased sessions, deterministic assignment,
  generations, restart invalidation, heartbeat responsiveness during storage stalls.
- **KF-006:** immediate bounded Fetch exposed over gRPC; Fetch never commits; durable
  monotonic commits, apply-time validation, rebalance barrier, independent groups,
  inactive-group reset, replay and restart tests.
- **KF-007:** bounded waiter registry; lost-wakeup, cancellation, deadline, ownership
  and lifecycle tests; many waiters without one worker per waiter.
- **KF-008:** retention boundaries, out-of-range errors, segment pinning, crash-safe
  deletion, quotas, degraded behaviour, bounded shutdown; preserve offset identity.
- **KF-009:** loopback defaults, Docker persistent volume demo, readiness/metrics,
  producer/consumer example, accurate README and actual CI results; publish only
  measured benchmarks with hardware/workload/durability details.
