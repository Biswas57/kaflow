# Kaflow V1 tickets and Codex working paper

## Board

Status: READY, IN_PROGRESS, BLOCKED, DONE. One active ticket maximum.
Last updated: 2026-10-16. Current ticket: **none**. Latest completed: **KF-007**.

| Ticket | Scope | Status | Dependencies |
| --- | --- | --- | --- |
| KF-001 | Reproducible source build and honest baseline | DONE | None |
| KF-002 | Injected broker ownership and V1 record/API contracts | DONE | KF-001 |
| KF-003 | Partition-log framing, indexes, rotation, bounded immediate reads | DONE | KF-002 |
| KF-004 | Durable publication and crash recovery | DONE | KF-003 |
| KF-005 | Leased groups and generation fencing | DONE | KF-004 |
| KF-006 | Durable commits, immediate Fetch API, independent replay | DONE | KF-005 |
| KF-007 | Asynchronous long polling and cancellation | DONE | KF-006 |
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

## KF-003 — Partition-log framing, indexes, rotation, bounded immediate reads

**Status:** DONE (2026-10-13).
**Publication:** User authorized commit and push on 2026-10-14; no PR requested.
**Branch:** `codex/kf-003-partition-log`.
**Base:** `323ec4023c0c04aab29078064f10ac02f0ac893b` (KF-002).
**Dependency at branch creation:** KF-002 was not merged. Fetched `origin/main` was
`a704b7067710c776872c4ba60a453fba3308da52`, which does not contain KF-002.
This is a dependent branch; the checkout was clean before branch creation.
**Design:** [docs/design/kf-003-partition-log.md](docs/design/kf-003-partition-log.md).
**Detailed evidence:** [docs/baseline/kf-003.md](docs/baseline/kf-003.md).

### Scope and decisions

Independent, file-backed storage for one qualified partition per explicitly selected
owned directory. Reuse immutable values; storage alone assigns contiguous offsets.
Opening thread owns serialized append, immediate read and close. No worker pipeline,
concurrent read workers, in-memory broker integration, hidden adapter or migration.
Versioned big-endian segment/record frames use CRC32C, exact opaque bytes and strict
UTF-8 identity. Headers count toward immutable segment limits. Sparse derived indexes
are validated against complete log scans, streamed/rebuilt with bounded memory.
Capture completed-write boundaries for bounded immutable reads. File locks protect
canonical directories, including separate JVMs. Failure after mutation latches failure
without truncation or progress advancement; reopening validates complete logs only.
No durable acknowledgement/publication, recovery repair, Fetch/Commit, leases, long
polling, retention, V1 registration, public storage admin API or KF-004 work.
The implementation request authorized local work only; commit and push were subsequently
authorized on 2026-10-14. No PR, merge or KF-004 work is authorized.

### Acceptance

- [x] Write and review exact storage format, crash states, bounds and lifecycle design before code.
- [x] Preserve all existing 40 tests and unregistered V1 network boundary.
- [x] Preserve binary/empty payloads, key presence, timestamps and qualified identity.
- [x] Literal/independent fixtures; reject malformed versions/flags/lengths/checksums/identity/offsets.
- [x] Correct short I/O, bounded zero progress, truthful write receipts and failed-writer semantics.
- [x] Exact-limit rotation, oversized rejection, unique segments and contiguous offsets.
- [x] Sparse bounded index lookup/rebuild; derived damage never conceals corrupt log frames.
- [x] Bounded immutable reads across segments, captured boundary, continuation/end and byte-budget errors.
- [x] Clean reopen; separate directories; canonical same-process and separate-JVM ownership.
- [x] Initialization failure/close cleanup, primary-error preservation, thread ownership and idempotence.
- [x] Fresh source-only build/package, separate full tests, packaged smoke and recorded reports.
- [x] Diff/claim review and exact KF-004 handoff; no automatic publication or next-ticket work.

### Evidence and progress

- Independently verified [KF-002 Linux run 38377151066](https://github.com/Biswas57/kaflow/actions/runs/38377151066):
  revision `323ec4023c0c04aab29078064f10ac02f0ac893b`, completed/success; the build
  job and compile/package, tests, packaged smoke and report upload steps all succeeded.
  This is inherited evidence, not a KF-003 run. Raw API responses retained with local evidence.
- Design recorded before code. Implemented independent `kaflow.storage` format, bounded
  positional I/O, thread-owned partition log, OS directory ownership, rotation, streamed
  index validation/rebuild and captured-boundary reads. Existing core/prototype untouched.
- Added independent/literal format fixtures, corruption matrices, short/zero I/O and
  partial-failure tests, rotation/read/index checks, and same/separate-JVM ownership
  probes with bounded teardown.
- Development full test run: exit 0 in 11.96 s; 110 executed/passed, zero
  failures/errors/skips/timeouts. All original 40 tests unchanged and passing.
- Review added file-size checks before index entry reads, rejection of nonregular lock
  files before opening them, and failure latching when a byte-budget rejection also
  encounters a cleanup error. Added focused regressions, bound configuration checks
  and all-segments-before-index-repair evidence before final source-only validation.
- Fresh candidate: `/private/tmp/kaflow-kf003-73u8mwzn/candidate`, 161 intended inputs,
  87 Java files and two schemas, hash/mode manifest; no project outputs or build caches.
  Environment: macOS 26.6.2 arm64, Temurin 17.0.20.1+1, Gradle wrapper 8.5. Reused
  verified temporary tooling/dependency downloads; system defaults unchanged.
- `./gradlew clean :app:classes :app:testClasses :app:bootJar --no-daemon`: exit 0,
  6.74 s; 18 generated Java files, production/tests compiled, executable JAR packaged.
- Separate `./gradlew :app:test --rerun-tasks --no-daemon`: exit 0, 9.89 s;
  **120 discovered/executed/passed**, **0 failed**, **0 errors**, **0 skipped**,
  16 classes. Original 40 unchanged; 80 storage invocations. No setup/teardown/task
  timeouts. Both Gradle commands bounded at 600 s.
- `python3 scripts/smoke_bootjar.py`: exit 0; HTTP/gRPC ports 61618/61619,
  health 200/UP, administration create/read 201/200, SIGTERM exit 143 in 0.456 s;
  both listeners closed, no error/timeout/forced kill. Startup evidence only.
- Real standalone and Spring V1 Append calls still returned UNIMPLEMENTED. Existing
  production/tests, schemas, build configuration, wrapper and smoke are byte-for-byte
  unchanged. Scope, resource/failure ownership, whitespace and unsupported claims reviewed.
  Built inputs match the tested manifest; final evidence/source-of-truth changes are docs only.
- Raw logs, manifest, fresh XML/HTML and smoke result/application log retained in ignored
  `build/kf003-evidence/`; durable details and report paths in `docs/baseline/kf-003.md`.
  No failed build/test attempt or remaining blocker. No KF-003 Linux CI result claimed.
  Completed writes and valid clean reopen do not establish crash/power-loss durability.

### Exact next action

Commit and push this reviewed, validated change as authorized. Verify the resulting
Linux workflow before recording KF-003 CI success; no PR requested or KF-004 work
started. Keep V1 unregistered. The exact KF-004
handoff is **durable publication, bounded checkpoints, crash recovery, directory
synchronization, and late storage completion policy**. Integrate writer/completion
ownership and bounded read workers without disk I/O on coordination; never replace a
possibly running writer or release its directory lock on caller timeout. Preserve
acknowledged retained data and decide tail handling using durable publication evidence.

## KF-004 — Durable publication and crash recovery

**Status:** DONE (2026-10-14); final local acceptance passed.
**Branch:** `codex/kf-004-durable-publication`.
**Committed base:** `d9fa545abbed4493c36ede933f32d36b9f1ee7d9` (KF-003).
**Inherited inputs:** clean checkout, no staged/unstaged/untracked source changes;
162 file SHA-256/mode manifest in ignored `build/kf004-evidence/inherited-manifest.json`.
The supplied ticket's uncommitted-KF-003 summary is stale: user-authorized commit/push
preceded this request. Fetched main `d2e352c340f07d046abbc7ab0387113ccd1aded2` contains
KF-002 but not KF-003. This remains a dependent branch; no work was discarded.
**Design:** [docs/design/kf-004-durable-publication.md](docs/design/kf-004-durable-publication.md).
**Publication:** the user's final-validation request on 2026-10-14 explicitly authorizes
commit and push of this branch after passing acceptance. It supersedes the implementation
request's publication restriction. No PR, merge or KF-005. Verify CI against the exact pushed
revision; no KF-004 CI result is claimed in this pre-publication record.
**Detailed evidence:** [docs/baseline/kf-004.md](docs/baseline/kf-004.md).

### Scope, decisions and stages

One dedicated storage owner; responsive coordinator; bounded admission, queues and response
workers. Globally serialize mutations; publish only after forced record/name persistence and
atomic forced per-partition checkpoints. Durable bounded catalog changes only at topic creation.
Recover published data exactly; discard only tails proven unpublished by valid metadata.
Latch degradation after watchdog/failure and retain ownership until actual writer completion.
Explicit durable Spring configuration registers unary V1 Append after successful recovery.
Default prototype/volatile core remain separate. No Fetch/Commit, leases, retention or migration.
The design records formats, checkpoint cost, filesystem assumptions and crash-state policy.

Reviewable stages within this ticket: KF-004.1 storage protocol/recovery; KF-004.2 coordinator,
opt-in transport/lifecycle; KF-004.3 deterministic failures, process/network evidence and docs.
Only KF-004 is active; these stages are not permission to start a later milestone.

### Acceptance

- [x] Reconcile actual ancestry/inputs and verify inherited evidence; record design before code.
- [x] Durable topic metadata, exact identity and bounded checkpoint protocol; no volatile success.
- [x] Preserve KF-003 framing/rotation; explicit force and directory synchronization ordering.
- [x] Validate all published data; conservative tail repair; interrupted/repeated recovery.
- [x] Bounded coordinator/writer/response ownership, routing, admission and exact reservation release.
- [x] Monotonic watchdog, responsive diagnostics, safe late completion/cancellation/shutdown.
- [x] Opt-in real V1 Append success only after publication; default UNIMPLEMENTED remains.
- [x] Deterministic persistence faults, disk-full, corruption, process termination and restart tests.
- [x] All existing assertions preserved; source-only build/full tests and both packaged smokes.
- [x] Record exact results, limitations and KF-005 handoff; review inherited versus new diff.

### Evidence and progress

- Inspected retained KF-003 XML: 120 tests, 16 classes, zero failures/errors/skips; build/test
  exit 0 and prototype smoke passed. Production inputs matched the tested manifest before
  KF-003 commit. Independently inspected completed Linux run 38472166665 at `d9fa545`:
  build job completed/success. A separate run 38472307492 was in progress; no outcome claimed.
- Commands planned: `./gradlew clean :app:classes :app:testClasses :app:bootJar --no-daemon`,
  separate `./gradlew :app:test --rerun-tasks --no-daemon`, `python3 scripts/smoke_bootjar.py`,
  plus bounded durable-mode packaged smoke. Use Java 17, wrapper 8.5 and fresh source snapshot.
- Implemented KF-004.1 durable catalog/checkpoints, force/name ordering and conservative two-phase
  validation/repair; KF-004.2 bounded coordinator/writer/response ownership, watchdog and opt-in
  Spring V1 Append. Existing prototype paths and all 120 baseline assertions preserved.
- Development compile passed. Focused storage and coordinator suites passed. Network/crash run:
  12 tests, one new test failed because native oversized transport rejection surfaced CANCELLED,
  not application RESOURCE_EXHAUSTED. Separated the transport boundary from application status
  mapping and verified no publication; eight process halt cases passed.
- Expanded focused suite: 67 tests, one new inspection assertion failed after strengthening
  inspection admission to reserve requested return bytes. Adjusted the fixture to request its
  configured three-byte budget and explicitly test oversized inspection-budget rejection.
  Original failing XML/logs retained; no baseline assertion weakened.
- Added packaged durable smoke with an external real-gRPC client that forces received receipts,
  kills the first broker process, restarts, appends at recovered next offsets and inspects exact
  records offline. Added CI invocation/report upload; no KF-004 CI execution claimed.
- Full development suite passed: 188 tests, zero failures/errors/skips. First clean candidate
  built in 6.74 s and passed 188 tests in 17.69 s. Both commands exited 0 without timeouts.
- Review added a durable marker on the existing owning root lock channel: a missing catalog
  in an initialized empty store must fail closed rather than reinitialize. Added two regression
  tests; also verified root exclusion from another JVM while a watchdog-expired writer runs.
- The marker candidate built in 6.68 s and passed 190 tests in 18.92 s. Final review also
  rejects uncatalogued topics with record/nonzero publication evidence as inconsistent,
  including when their data is truncated. Added a regression before final validation.
  No KF-004 CI run or power-loss proof claimed.
- Final post-marker/post-orphan source-only snapshot: 183 input files, 105 Java sources,
  two Protobuf schemas, no copied generated/build artifacts. SHA-256/modes matched the
  checkout before final evidence-only document edits. Compile/package passed in 8.59 s;
  separate full suite passed in 21.34 s: **191 tests, 21 classes, zero failures/errors/skips**.
  All commands exited 0; no command, child-process or shutdown timeout occurred.
- Original packaged smoke passed in 2.01 s: health UP, HTTP/gRPC listening, REST create 201
  and get 200; SIGTERM exit 143 in 0.450 s, both listeners closed. Default V1 remained
  unregistered, also proven by the retained network-boundary test.
- Durable packaged smoke passed in 4.93 s: external real-gRPC receipts at offsets 0/1,
  forced witness, intentional SIGKILL exit -9, restart receipts at 2/3, SIGTERM exit 143
  in 0.454 s. Both instances ready and all listeners closed; offline inspection verified
  all four records' exact identity/offset/timestamp/key presence/bytes. No smoke errors.
- Final environment: macOS 26.6.2 aarch64, Temurin 17.0.20.1+1, wrapper 8.5, APFS.
  Linux local storage remains the reference, requiring honored file/device flushes,
  atomic same-directory replacement and directory sync. Process crashes do not prove
  physical power-loss durability. Unknown failed/canceled outcomes may survive and retries
  may duplicate. Missing/corrupt authoritative metadata fails closed; no KF-003 migration.
- Final reports/logs/manifest and review summary retained under ignored
  `build/kf004-evidence/final/` and `build/kf004-evidence/candidate-manifest.json`.
  Reviewed correctness, scope, secrets and tracked outputs: no unresolved findings, no
  secret-pattern matches or generated/build outputs to commit. No schema/dependency/wrapper
  changes; all 120 inherited assertions preserved. No unresolved acceptance blockers.

### Handoff

KF-005 remains unstarted: durable group definitions, leased membership and generation fencing.
No public Fetch or consumer progress in KF-004. Record measured local/CI evidence separately;
process termination is not physical power-loss proof.
Next action under the latest authorization: commit/push KF-004, inspect the exact pushed
revision's GitHub Actions run and report its URL/outcome separately from local evidence.
CI is configured for clean build, full tests, both packaged smokes and report upload;
configuration alone is not execution evidence. Do not merge or start KF-005.

## KF-005 — Durable groups, leased membership and generation fencing

**Status:** DONE (2026-10-14), locally validated; publication authorized. **Branch:** `codex/kf-005-leased-groups`.
**Verified merged base:** `7d1958a539d4dd5f5dd4fefa348b031d0e1e62ca` on clean main,
also remote main; contains KF-004 commit `22babbbb32367f5ee2e5e0585469e1a1d9d01ca9`
and has the same source tree. No unrelated uncommitted inputs. A 183-file base manifest
is retained under ignored `build/kf005-evidence/base-manifest.json`.
**Design:** [docs/design/kf-005-leased-groups.md](docs/design/kf-005-leased-groups.md).
**Publication:** User authorized commit and push on 2026-10-14, followed by GitHub Actions
verification for the exact pushed commit. This supersedes the implementation request's
publication restriction. No PR or merge requested; do not begin KF-006.

### Scope, decisions and acceptance

Durable exact one-topic group bindings; coordinator-owned ephemeral sessions; fresh
broker incarnation; monotonic leases; Join-order modulo assignment; current-generation
apply-time fencing. V1 unary Join/Heartbeat/Leave are opt-in with durable Append.
Catalog V2 embeds bounded definitions; V1 opens with zero groups. Separate bounded
control/response capacity keeps membership responsive during a stalled writer.
No Fetch, Commit, committed offsets, replay, reset, long polling or retention.

Stages: KF-005.1 format/definition recovery; KF-005.2 membership/control scheduling;
KF-005.3 wire protocol, deterministic/network tests, validation and handoff.

- [x] Verify merged base/clean inputs; record ownership, wire, leases, bounds and compatibility before code.
- [x] Durable idempotent group binding; V1 compatibility, failpoints and fail-closed metadata recovery.
- [x] Independent groups, literal assignments, bounded members/timers and exact monotonic expiry.
- [x] Incarnation/session/generation/partition fencing; live stale-generation members can renew.
- [x] Retry/duplicate-label/Leave/cancellation/close semantics; exact reservation release.
- [x] Responsive controls under blocked storage and latched degradation; safe late completion/locks.
- [x] Generated unary membership schema and real network/restart tests; prototype isolated.
- [x] Preserve prior tests; final source-only build, full suite and both packaged smokes.
- [x] Record actual counts/errors/timeouts, review diff and exact KF-006 handoff.

### Evidence and next action

Inherited KF-004 exact-commit CI was independently verified earlier in this chat:
[run 38492040645](https://github.com/Biswas57/kaflow/actions/runs/38492040645), revision
`22babbbb32367f5ee2e5e0585469e1a1d9d01ca9`, completed success; build, full tests,
both smokes and upload succeeded. This is inherited evidence, not a KF-005 run.
Validation commands: `./gradlew clean :app:classes :app:testClasses :app:bootJar --no-daemon`,
`./gradlew :app:test --rerun-tasks --no-daemon`, `python3 scripts/smoke_bootjar.py`,
`python3 scripts/smoke_durable.py`; Java 17, source-only candidate, bounded runner.
Implemented catalog V2/read-only V1 compatibility, coordinator sessions/generations,
bounded separate controls, unary membership RPCs and focused/network/crash tests.
Initial compile failed on a lambda capture (corrected); 42 focused tests then passed,
full development suite passed 240 tests, and 54 review tests passed. All test runs had
zero failures/errors/skips. Review strengthened response-byte accounting and added wire
bounds fixtures. Details in [docs/baseline/kf-005.md](docs/baseline/kf-005.md).
Final source-only snapshot: 198 inputs, 118 Java files, two schemas. Build/package passed
in 7.69 s; separate full suite passed in 27.00 s: **248 tests, 27 classes, zero failures,
errors or skips**. Original packaged smoke passed in 2.37 s; durable packaged crash/restart
smoke passed in 6.16 s. All four commands exited 0 without timeouts. Prototype health/admin
and shutdown passed; durable smoke retained acknowledgements 0/1 across intentional SIGKILL,
resumed at 2/3, and verified all four exact records. All selected listeners closed, no smoke
errors or application WARN/ERROR entries. Membership round trips/restart fencing were exercised
by the real-network suite; the existing durable packaged smoke continues to verify Append.

Actual environment: macOS 26.6.2 aarch64, Temurin 17.0.20.1+1, wrapper 8.5, APFS checkout
and candidate. Linux local storage remains the reference; honored force/atomic-replace/
directory-sync semantics are required, and process-halt tests are not physical power-loss
proof. Join retries can leave additional sessions until expiry; tokens are not transport
authentication. Catalog V2 is not readable by old KF-004 binaries. This pre-publication
record claims local validation only; the exact pushed revision still requires CI verification.

Final diff review: 27 changed/new files, no unresolved findings/blockers, secret-pattern
matches or tracked generated/build outputs. All production/test/schema/build/smoke inputs
match the validated manifest; final changes are evidence-only docs. Evidence under ignored
`build/kf005-evidence/final/`, with manifests and earlier results alongside it.
The only original assertion update is the intentional service inventory extension;
existing Append and durability assertions remain, with added prototype membership rejection.
Exact next action under the latest authorization: commit and push the reviewed change,
then verify GitHub Actions for that exact revision and report its URL/outcome separately
from local evidence. Do not open a PR, merge or begin KF-006.

### Handoff

KF-006 remains unstarted: immediate bounded Fetch, durable next-offset Commit, apply-time
ownership validation and commit/rebalance barrier around unresolved applying writes.

## KF-006 — Immediate bounded Fetch, durable commits and independent replay

**Status:** DONE (2026-10-14), locally revalidated; follow-up CI verification pending. **Branch:** `codex/kf-006-fetch-commit`.
**Verified merged base:** `6f1b667785ee770427fcc08963fa24d0f216d6c4` (remote main,
merge PR #5); contains `66e3bfc6fbeeeb7051cb377806c3c9c1987544ed` with identical tree.
Started with a clean checkout; no unrelated inputs. Base manifest is retained under
ignored `build/kf006-evidence/`. Local main remains at its earlier revision; the new
branch was created directly from verified `origin/main`.
**Publication:** User authorized validation, commit and push on 2026-10-14, followed by
exact-commit GitHub Actions verification. No PR, merge or KF-007.
**Design:** [docs/design/kf-006-fetch-commit.md](docs/design/kf-006-fetch-commit.md).

### Scope, decisions and acceptance

Single-partition immediate Fetch and durable next-offset Commit; independent group
progress, replay without progress mutation, and internal inactive-group reset. Immutable
writer-issued snapshots, independent bounded positional readers, admission/completion
fencing, and group-wide application barriers. Catalog V3 makes bounded per-group progress
files authoritative; old V1/V2 stores upgrade on first progress mutation. No long polling,
retention, transactions, delivery tracking, cross-partition batches or exactly-once claim.
Stages: KF-006.1 storage/progress and read ownership; KF-006.2 consumer scheduling/barriers;
KF-006.3 protocol, deterministic/network/crash tests, packaged lifecycle and final evidence.

- [x] Verify merged ancestry, clean inputs, inherited evidence and document design before code.
- [x] Immutable read handles, captured publication, independent channels and bounded resources.
- [x] Fetch admission/completion fencing, corruption policy, cancellation and shutdown cleanup.
- [x] Versioned durable progress, upgrade, strict recovery, independent groups and idempotency.
- [x] Writer-ready application validation, group barrier, expiry/Leave and ambiguous/late outcomes.
- [x] Bounded committed assignment positions, inactive reset and safe Join/reset race.
- [x] Unary Fetch/Commit transport, exact bytes/key presence, sanitized status and prototype isolation.
- [x] Deterministic limits/races/failpoints/recovery tests and public consumer packaged restart smoke.
- [x] Clean source build/package, separate full suite, original and actual durable packaged smokes.
- [x] Final diff review, actual evidence/limitations and KF-007 handoff.

### Evidence, progress and exact next action

Retained KF-005 final XML independently summed: 248 tests, zero failures/errors/skips.
Inherited exact-commit [CI run 38495562718](https://github.com/Biswas57/kaflow/actions/runs/38495562718)
was verified in this chat: build, full tests, both smokes and report upload succeeded for
`66e3bfc6fbeeeb7051cb377806c3c9c1987544ed`. This is not KF-006 execution evidence.
Inspected KF-003/004/005 designs, baselines, storage/coordinator/membership/transport code.
Validation commands: `./gradlew clean :app:classes :app:testClasses :app:bootJar --no-daemon`,
`./gradlew :app:test --rerun-tasks --no-daemon`, `python3 scripts/smoke_bootjar.py`,
`python3 scripts/smoke_durable.py`. Use Java 17, bounded runners and source-only final inputs.
Implemented immutable writer-issued read snapshots, independent bounded readers, coordinator
completion fencing, group-wide apply-time commit/reset barriers, catalog V3 and bounded
per-group progress files, assignment positions and unary public Fetch/Commit. Extended the
existing durable smoke to two independent public consumers and restart/resume.
Development compile passed (5.95 s). Initial inherited full suite: 248 tests, one failure
because the unknown catalog version fixture used newly supported V3; moved that sentinel to
V4, preserving the unknown-version assertion. Raw failed XML/logs retained. Focused suites
then passed 47 and 75 invocations with zero failures/errors/skips. Added further independent
reader-channel/short-read, concurrent Append/read, uncertain reset and qualified-partition
progress tests. Full development suite passed 326 invocations in 39.96 s. First clean candidate build,
326-test suite and original smoke passed. Its extended durable smoke exited 1 because
standalone Netty DEBUG text preceded the confirmation line, despite successful public
consumer phases and recovery. Configured fixture logging to stderr at INFO and required
exact phase confirmations; retained the failed report and reran all final checks.

Final source-only candidate: `/private/tmp/kaflow-kf006-final-7v59prm4`, 215 inputs,
133 Java sources/two schemas. Accepted clean build/package exited 0 in 7.76 s; separate
full suite exited 0 in 39.95 s: **326 tests, 34 classes, zero failures/errors/skips**.
Original packaged smoke passed in 2.45 s; extended durable smoke passed in 8.17 s. No
command/client/test-child/shutdown timeout. Prototype health/admin/shutdown passed. Durable
smoke acknowledged Append 0/1 and two group commits 2/1, survived SIGKILL, rejoined/resumed
at 2/1, appended 2/3, committed 4/3 and shut down normally; all four exact records matched
the external witness. All listeners closed, empty smoke error lists, no application
WARN/ERROR entries. [Full evidence](docs/baseline/kf-006.md), ignored reports/manifests in
`build/kf006-evidence/final/` and adjacent retained development/failure directories.

Actual environment: Java 17.0.20.1 Adoptium, macOS 26.6.2 aarch64, wrapper 8.5, APFS
checkout/candidate. No KF-006 Linux CI execution claimed. Linux local storage remains
reference; honored force/atomic replacement/directory sync is required. Process kills do
not prove power-loss durability. Catalog V3 rejects downgrade; Commit does not prove
processing; canceled/timed-out writes can have unknown outcomes. Bound accounting excludes
caller-held values, transport buffers after handoff and total heap. No public reset endpoint.

Final review: 36 changed/new files; no unresolved findings/blockers, credential-pattern
matches or intended generated outputs. Final code/test/schema/build/smoke matches the tested
manifest; later edits are documentation only. No dependency/wrapper/workflow changes.
Publication revalidation passed under the user's 2026-10-14 authorization. Re-fetched
origin/main and verified the same base and identical merged KF-005 tree. Reviewed all
36 changed/new files, especially apply-time fences/barrier lifetime, late completions,
read ownership/publication/cleanup, recovery, reset/Join and V3 compatibility; no blocker
or production fix found. Fresh source-only snapshot:
`/private/tmp/kaflow-kf006-publish-dvwavl45/candidate`, 215 inputs, 133 Java files/two schemas.
Clean build/package passed in 9.86 s; separate full tests passed in 39.99 s. Recomputed fresh
XML: **326 tests, 34 classes, zero failures/errors/skips**. Original packaged smoke passed
in 2.40 s and extended two-group Fetch/Commit crash/restart smoke in 8.15 s. All exits 0,
no timeouts; all listeners closed, replay preserved progress, restart resumed 2/1 then
committed 4/3, and the four-record witness matched. No application WARN/ERROR entries.
New raw reports/manifests/environment/counts are in ignored `build/kf006-evidence/publication/`;
[publication validation record](docs/baseline/kf-006.md#authorized-publication-validation--2026-10-14)
contains ports, shutdown outcomes, JAR hash and review details. The same macOS/APFS/Java 17
filesystem assumptions and limitations above apply. V3 has no supported downgrade; checksums
do not detect replacement with older otherwise-valid metadata. No generated outputs or
credential-pattern matches; original smoke, dependencies, wrapper and workflow unchanged.
Final executable inputs match the manifest; later edits only record documentation.

Exact next action at this prepublication checkpoint: review the staged diff, commit KF-006
and push `codex/kf-006-fetch-commit` to origin, then verify GitHub Actions for that exact SHA
and report its URL/outcome. The configured CI runs build/package, full tests, both smokes and
report upload; no KF-006 CI result is claimed before execution. No PR, merge or KF-007.

### Exact-commit CI follow-up

Initial authorized publication: `c0f9462c93ea88cd028ad0c02f814fcbf489f108` on
`origin/codex/kf-006-fetch-commit`. [CI run 38506226589](https://github.com/Biswas57/kaflow/actions/runs/38506226589)
built successfully but its full suite failed: fresh uploaded XML has 326 tests, one failure,
zero errors/skips (325 passed). Both smokes were skipped; report upload succeeded.
`DurableBrokerTest.concurrentAcceptedResultsExactlyMatchContiguousRecords` requested the
entire inspection byte budget after observing append results but before the final response
reservation necessarily unwound. RESOURCE_EXHAUSTED was valid; this was a test synchronization
race, not a failed append. Added bounded `awaitIdle` before full-budget inspection and
strengthened the blocked-response test to deterministically prove the same byte-reservation
lifetime and subsequent successful inspection. All 64-record/offset/payload assertions remain;
no production change, limit increase, ignored rejection, retry or weakened assertion.
Failed CI job log and uploaded reports retained under `build/kf006-evidence/publication/`.
Follow-up candidate `/private/tmp/kaflow-kf006-ci-fix-8bcr72yi/candidate` has 215 source-only
inputs. Clean build/package exit 0 in 9.67 s; focused broker suite exit 0 in 10.48 s (9 tests).
Separate full suite exit 0 in 37.79 s: recomputed 326 tests, 34 classes, zero failures/errors/skips.
Original smoke exit 0 in 2.40 s; extended two-group crash/restart smoke exit 0 in 8.21 s.
No timeouts, all listeners closed, original exact receipt and independent resume/replay
assertions passed; no application WARN/ERROR entries. New manifest, focused/full XML and
complete smoke/command reports retained in `build/kf006-evidence/publication-followup/`.
Final source matches that candidate, with only evidence docs edited afterward. Reviewed
three follow-up files (test plus two documents), now 37 changed files against KF-005;
no production change, secrets, generated artifacts or unrelated changes. Commit/push the
authorized follow-up and verify its exact SHA in Actions before reporting completion.
The first failed CI remains recorded and cannot substitute for this verification.
No PR, merge or KF-007.

### Handoff

KF-007 remains unstarted: bounded asynchronous waiters, lost-wakeup prevention, cancellation,
leases/ownership revalidation and no read worker occupied while waiting.

## KF-007 — Bounded asynchronous long polling and cancellation

**Status:** DONE (2026-10-16), local validation and publication review passed. **Branch:** `codex/kf-007-long-polling`.
**Base:** `09b42a45b056b842f3c3ee333a6faea4d549c885`, fetched origin/main, merge PR #6.
Verified ancestry contains KF-006 `759f71d6a6ea0b2050a1221d5e33615c69ed9eaf`; identical tree.
Clean checkout, no unrelated edits. Base manifest: ignored `build/kf007-evidence/`.
**Publication:** User authorized commit/push and exact-SHA CI verification on 2026-10-16.
No PR, merge or KF-008.
**Design:** [bounded long polling](docs/design/kf-007-long-polling.md).

### Scope, decisions and acceptance

Extend unary Fetch with additive wait field; zero remains immediate. Park only at the
published end, holding bounded metadata and completion capacity, no reader/handle/buffer.
Coordinator owns indexed waiters, publication notifications and removable timer slots.
Fair bounded notification batches; activation rejects overload explicitly. Reserve full
read charge before snapshot acquisition; keep charges through actual work and delivery.
No persistence-format change, subscriptions, minimum batch, public test endpoint or retention.

Stages: KF-007.1 state machine/limits/registry; KF-007.2 coordinator/transport cancellation;
KF-007.3 deterministic/network tests, packaged observability and final source-only validation.

- [x] Preserve immediate Fetch, committed progress, membership and durability guarantees.
- [x] Add bounded immutable wait options, indexed metadata/timer/notification limits.
- [x] Prevent lost wakeups; coalesce publications and process fair bounded batches.
- [x] Admission/activation/response fencing; deadlines, expiry, barriers, cancellation/close.
- [x] Retain actual read and response ownership; zero read resources while parked.
- [x] Deterministic races, independent groups, every resource bound and repeated cleanup tests.
- [x] Real gRPC wait/publication/expiry/cancellation/fencing tests.
- [x] Packaged registered-wait proof and exact bytes, preserving two-group crash/restart smoke.
- [x] Clean build/package, separate full suite, original and durable smokes; actual evidence.
- [x] Final diff review, no unrelated/generated/credential changes; KF-008 handoff only.

### Evidence and next action

Read KF-004/005/006 designs, validation records and current coordinator/storage/membership/
transport. Recomputed retained final KF-006 CI XML: 326 tests, 34 classes, 0 failures/errors/skips.
[Run 38506949852](https://github.com/Biswas57/kaflow/actions/runs/38506949852) was independently
verified for `759f71d6a6ea0b2050a1221d5e33615c69ed9eaf` in this chat; build, tests, both smokes
and upload succeeded. This is inherited evidence, not a KF-007 run. Preserve the strengthened
response-reservation test: result visibility does not imply delivery accounting has released.

Validation: Java 17 and wrapper; `./gradlew clean :app:classes :app:testClasses :app:bootJar --no-daemon`,
`./gradlew :app:test --rerun-tasks --no-daemon`, `python3 scripts/smoke_bootjar.py`,
`python3 scripts/smoke_durable.py`. Use a fresh source-only candidate and bounded runners.
Implemented immutable wait limits/cancellation flag, indexed removable waiter registry,
coalesced fair partition notifications, elapsed wait deadlines, activation/read accounting,
barrier markers and early transport cancellation. Initial compilation passed (11.45 s);
inherited full suite passed: 326 tests, zero failures/errors/skips (38.75 s).
First deterministic polling/resource/registry suite passed: 38 tests (12.61 s); raw XML retained in
`build/kf007-evidence/polling-tests-1/`. Covers ordered registration/publication, timeout ties,
clock wrap, membership/barriers, every admission dimension, cancellation phases, saturation,
64 parked/no-reader requests and repeated cleanup. Six actual gRPC tests passed (18.22 s).
Additional coordination/schema checks plus packaging passed: nine tests (8.20 s), covering
resolved barriers/late reads, full ingress cancellation and hot/quiet/control progress.
Extended durable development smoke passed (9.98 s): preserved two-group crash/restart checks
plus independently observed parked Fetch from production classes extracted from the actual JAR.
Final source-only candidate `/private/tmp/kaflow-kf007-final-wd7pwr_g/candidate` contains
226 inputs, 142 Java files/two schemas. Clean build/package passed (9.17 s); separate full
suite passed (56.11 s): recomputed **378 tests, 39 classes, zero failures/errors/skips**.
Original smoke passed (2.46 s). First final durable smoke passed (10.02 s) with one benign
Spring logging-property warning. Selected the appropriate property per fixture, then reran
the affected durable smoke: passed (10.09 s), zero WARN/ERROR entries in accepted smoke logs.
No command/client/test-child/shutdown timeout. No production/test/schema/build changes after
full validation; updated smoke script matches its accepted run. Final evidence edits are docs only.

Prototype health/admin/shutdown passed. Durable groups resumed at 2/1 after SIGKILL, then
committed 4/3; four exact records matched the external acknowledgement witness. Separate
Spring fixture from that same JAR proved one parked Fetch and zero readers before Append,
exact returned bytes/identity/offset/timestamp, unchanged committed position 0 and complete
cleanup. All listeners closed. [Full validation record](docs/baseline/kf-007.md) contains
commands, ports, shutdown times, JAR hash and report locations. Ignored evidence lives in
`build/kf007-evidence/final/` and adjacent development/command-log directories.

Final review covers all 22 changed/new files: lost wakeups, coalescing, timer/queue bounds,
current ownership/transient barriers, cancellation, late completion and reservation lifetime.
No unresolved blocker, unrelated edits, credential-pattern matches or generated artifacts.
Dependencies/wrapper/workflow and original smoke unchanged; executable inputs match the
validated manifest. Local environment: macOS 26.6.2 arm64/APFS, Temurin 17.0.20.1+1, wrapper 8.5.
No KF-007 Linux CI execution claimed. Existing CI would run build/package, tests, both smokes
and report upload after authorized publication; nothing was committed, pushed or published.

Logical budgets exclude caller memory, transport buffers after handoff and total heap.
Read-capacity overload rejects explicitly; no exact wall-clock timeout or scalability claim.
File/directory force, atomic replacement and honored device flushes remain assumptions;
macOS process-kill evidence is not physical power-loss proof. Catalog V3 downgrade remains
unsupported; no format/migration or durability guarantee was added.

### Authorized publication review — 2026-10-16

Re-fetched origin/main: unchanged `09b42a45b056b842f3c3ee333a6faea4d549c885`.
Verified KF-006 ancestry and identical merged tree. All 226 source-manifest entries are
present, with no new unrelated files; executable contents and modes match the accepted
candidate. Differences are evidence documents only. Recomputed retained XML: 378 tests,
39 classes, zero failures/errors/skips. Verified command logs/exit JSON, both smoke results,
exact two-group resume/commit confirmations, one parked/zero-reader delivery proof and JAR hash.
No affected executable input or incomplete evidence required a repeat build/test/smoke run.

Reviewed registration/publication ordering, cancellation/deadline and single-response paths,
current ownership and transient barriers, all queue/timer bounds, actual-work plus delivery
release, no parked read ownership, and degradation/close cleanup. No blocker or code fix.
Preserved all assertions. Staged review checks exact reviewed paths and manifest hashes,
whitespace, credentials, generated artifacts and unrelated changes. Raw publication audit:
`build/kf007-evidence/publication/`; local results remain distinct from subsequent CI.

At this prepublication checkpoint the next action is the authorized commit/push followed by
verification of Actions for that exact SHA. Configured CI includes clean compilation/package,
full tests, both smoke scripts (including parked delivery), and always-uploaded reports.
Record actual run/job outcomes after execution; a configured step is not a passing check.

### Handoff

KF-007 is approved for the requested publication; exact-SHA CI verification follows the push.
KF-008 remains unstarted: retention boundaries, out-of-range semantics, segment pinning,
crash-safe deletion, quotas, overload and bounded shutdown under load.

## Remaining ticket acceptance outlines

Expand the next eligible ticket into scope, decisions, steps, evidence, and handoff.

- **KF-008:** retention boundaries, out-of-range errors, segment pinning, crash-safe
  deletion, quotas, degraded behaviour, bounded shutdown; preserve offset identity.
- **KF-009:** loopback defaults, Docker persistent volume demo, readiness/metrics,
  producer/consumer example, accurate README and actual CI results; publish only
  measured benchmarks with hardware/workload/durability details.
