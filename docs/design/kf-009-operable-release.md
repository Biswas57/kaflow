# KF-009: operable V1 release

Contract and implementation record, 2026-11-08. **Native implementation validated; container acceptance BLOCKED by unavailable Docker.** [Working paper](../../todo.md), [evidence record](../baseline/kf-009.md).
Preserve the ownership, durability, fencing, retention and shutdown contracts in KF-002–008.

## Outcome and boundaries

Another engineer should be able to build from source, provision a fixed-partition topic,
start a durable broker, produce opaque bytes, consume and commit with two independent groups,
replace the process/container and recover acknowledged records and progress, inspect health
and bounded metrics, and reproduce measured workloads through the public V1 API.

Describe Kaflow as a lightweight single-node durable partitioned event-log broker with a
custom gRPC API. No Kafka client/wire compatibility, replication, automatic failover,
transactions, exactly-once processing, producer deduplication or production-readiness claim.
Append retries after ambiguous outcomes can duplicate records; Commit is not proof of
application processing. Process-crash evidence is not physical power-loss proof.
No new storage format, wire operation, remote reset, general-purpose SDK, authentication
system, framework migration or unrelated dependency upgrade belongs to KF-009.

## Starting state and implementation entry

Inspected branch: `codex/kf-008-retention-lifecycle`, HEAD and fetched `origin/main`
`3d9cdda18c616636edd411e6c470f01a046729a6` (KF-007 merge). KF-007 commit
`05248c86f4e7293810cc28c14ec12c26b4d60fbc` is an ancestor. KF-008 has 36 uncommitted
changed/new paths and no staged changes. Its 241-input manifest still matches all executable
inputs; only its four final evidence documents differ. Retained XML recounts 460 passing
tests in 44 classes; three smoke result files report success. These checks were inspected,
not rerun for KF-009. No KF-008 CI execution is implied.

Before implementation, recheck status, ancestry and evidence. If KF-008 is merged by then,
branch from verified main containing it. Otherwise create `codex/kf-009-operable-release`
from the completed working state, carrying the uncommitted KF-008 changes without committing
them. Capture hashes/modes of tracked and intended untracked inputs and both Git diffs;
distinguish inherited changes from KF-009 using this inventory, not `git diff HEAD` alone.
Mark KF-009 IN_PROGRESS only when implementation starts. Planning leaves it READY and keeps
the current branch. No commit, push, PR, merge, release or image publication is authorized.

At implementation entry, inspect prerequisites before container-specific work: resolve and
run the selected Java 17 `java` and `javac`, inspect `JAVA_HOME`, and run the wrapper version
check with the intended Gradle environment. Check Docker CLI and Compose versions, the active
Docker context, and bounded `docker version`/`docker info` calls that actually reach the engine.
Record client and server versions, context, engine OS/architecture and execution location
(native host, Docker Desktop VM or remote engine), separately from the client host. CLI
presence alone is not engine availability. Record required daemon/API access or missing
runtime/plugin/tooling setup precisely, without dumping credentials or unrelated environment.

Prerequisite inspection is separate from installing software, switching contexts or starting
services; this planning refinement authorizes none of those actions. Continue independent
implementation work when possible if a capability is unavailable, while leaving the exact
dependent validation command pending. Native checks cannot stand in for the container gate.
The earlier planning environment observations are historical and must be rechecked at entry.

## Startup, endpoints and configuration

Retain explicit `kaflow.durable.enabled=true` with a required data directory as the durable
selection. Make it the primary documented quickstart and Compose mode. Preserve the default
prototype mode for compatibility; label it volatile. Condition legacy REST `/api/**`, legacy
gRPC and their prototype state beans on prototype mode, preserving standalone/CLI use and
all prototype regression assertions. In durable mode expose V1 plus operator health/metrics,
not a misleading second namespace. V1 remains absent in prototype mode.

Set host defaults `server.address=127.0.0.1`, `server.port=8080`,
`grpc.server.address=127.0.0.1`, `grpc.server.port=9090`. Use one HTTP listener for health and
metrics, not an additional wildcard management listener. Test listening socket addresses
without address overrides, including absence of wildcard IPv4/IPv6 listeners; a connection
to localhost alone does not establish loopback binding. Deliberate non-loopback overrides
remain explicit and require an operator-managed trusted network.

Use typed Spring configuration bound once at startup to existing option records. Keep the
existing durable directory, segment, retention and quota property names compatible. Configuration
groups are `kaflow.durable.storage.*`, `broker.*`, `groups.*`, `consumer.*`, and `wait.*`;
existing segment/max-segment names remain canonical rather than adding competing aliases.
Add `kaflow.durable.shutdown-timeout` (10s default, positive, at most 5m) and bounded
`kaflow.durable.topics[index].name/partitions` provisioning. No hot reload.

The operator reference will enumerate every supported property, default, unit, range,
restart requirement and interaction. Scope the supported precedence to explicit CLI values
over environment over external configuration over packaged defaults; document Spring's
profile/config-file ordering and verify the documented combinations with packaged tests.
Reject unknown keys in the broker-owned configuration groups and malformed/out-of-range
values. Preserve intentional zero-byte budgets and configurations that limit allowable frames
through segment capacity; reject invalid combinations using existing constructors and
maintenance-headroom checks rather than silently changing their meaning.

| Configuration family | Preserve these existing defaults; expose/document supported bounds |
| --- | --- |
| Broker | 32 topics; 16 partitions/topic; 64 total; 128 admitted calls; 16 MiB charge; 30s watchdog |
| Storage | 16 MiB segments; 1,024 segments/partition; 16 KiB keys; 1 MiB payloads; index interval 128; read count 128; read bytes 2 MiB; zero-progress limit 8 |
| Membership | 128 groups; 32 members/group; 256 total; 32 pending joins; 128 controls; 2 MiB control charge; 128 KiB catalog; 30s lease |
| Consumer | 2 read workers; 16 admitted reads; 64 MiB read charge; 64 progress calls; 1 MiB progress charge |
| Wait | 256 admitted waits; 16/session; 64/partition; 512 KiB metadata at 2,048 bytes/call; 30s max wait; batch 8 |
| Retention/quota | Retention disabled (0); total 8 GiB; partition 1 GiB; conservative permanent metadata headroom |
| Transport/response | Document fixed worker/queue bounds and derived inbound limit; do not imply every internal bound is tunable |
| Shutdown/exposure | Shared total 10s budget; health and metrics only; no environment, config-properties, heap dump or shutdown endpoint |

Constructor ranges remain authoritative in `StorageOptions`, `DurableOptions`, `GroupOptions`,
`ConsumerOptions`, `WaitOptions` and `LifecycleOptions`. Reference tests will check default
mapping and boundary rejection so documentation cannot drift from the composed options.
Validate provisioning syntax/local bounds before opening storage and recovered-state conflicts
and aggregate capacity after recovery, before creating anything. An unavailable directory,
ownership conflict, failed recovery, conflicting topic or invalid configuration aborts startup;
there is no volatile fallback or usable V1 service after such failure.

Authentication and TLS are not configured by the inspected V1 composition. Session tokens
fence membership; they are not transport authentication. State plainly that the supplied
examples use plaintext trusted-local access. Do not expose an unauthenticated reset endpoint,
log payloads/tokens, or describe prototype custom crypto as V1 security.

## Durable startup provisioning

Choose a bounded startup topic list, not a new network service or offline admin program.
Default list is empty. Before opening storage, validate identifier encoding, blank names,
duplicate names, list length, partition counts and all configuration syntax/local bounds.
An empty list still allows the broker to start, but the quickstart always supplies its topic.

After exclusive recovery, preflight the entire requested list against recovered topic
definitions and aggregate configured capacity before creating any missing topic. Reject any
existing topic with a different fixed count. Count existing definitions once and add only
missing topics/partitions when checking total topic/partition capacity. Perform this bounded
metadata check on coordination while external V1 admission is still unavailable; do no disk
I/O there. A known conflict or insufficient aggregate capacity must not create an avoidable
durable prefix. Test a missing topic first and a conflicting existing topic late in the list:
startup rejects and the earlier missing topic remains absent after reopening. Test aggregate
capacity rejection similarly, including existing matching topics that consume no new capacity.

Add an internal ensure-topic operation alongside strict `createTopic`: coordinator application
checks existing count, succeeds without mutation for the same count, rejects a different count,
and otherwise dispatches the existing durable creation protocol. Retain strict create's
ALREADY_EXISTS contract. Do not implement check-then-create by interpreting arbitrary errors
as success or bypassing the writer/catalog protocol.

After the full recovered-state preflight succeeds, create missing topics sequentially with at
most one outstanding creation; ensure-topic still checks its definition/bounds at application.
Complete before V1 listener startup/application readiness. Wait on the startup thread, never
the coordinator. Use one bounded provisioning deadline derived from the configured watchdog;
timeout fails startup and invokes owned cleanup without releasing a still-running writer's
lock. A multi-topic list is not transactional: an I/O failure during creation can leave a
durable prefix even after successful preflight, and idempotent restart completes the remainder.
Test restart, preflight rejection without mutation, injected partial I/O failure and cleanup.
Do not expose inactive-group reset in this ticket; replay uses explicit Fetch offsets.

## Liveness, readiness and observability

Use existing Spring Actuator/Micrometer infrastructure, with
`/actuator/health/liveness`, `/actuator/health/readiness`, and `/actuator/metrics` plus its
per-meter endpoint. No Prometheus exporter, dashboard or new monitoring framework is required.
Do not expose detailed filesystem paths or exception messages in health responses.

Liveness means the application is running and able to answer its probe; it is not proof that
storage can write. A latched storage failure leaves a responsive process live but unready.
Readiness requires successful recovery/provisioning, live V1 transport, broker READY and
accepting admission, and no close request. It is false while starting, degraded or closing.
Unavailable probes before HTTP startup count as not ready. Ordinary bounded resource/quota
exhaustion is observable rejection and leaves readiness true; real I/O failure/ENOSPC and
watchdog degradation latch readiness false until restart/recovery. Preserve the prototype
aggregate-health smoke while testing durable readiness explicitly.

| Measurement | Kind and semantics |
| --- | --- |
| RPC outcomes/duration | Fixed method + gRPC status counters/timers for Append, Fetch, Commit and membership operations; one terminal observation per RPC |
| Admission/work | Gauges for admitted calls/bytes, active reads/writer, bounded queue occupancy, progress/control reservations, transport/response activity |
| Waiting/membership | Gauges for parked/admitted waits, metadata/timers/notifications, groups and sessions; fixed fencing outcome counters |
| Storage | Gauges for charged segment/index bytes, conservative metadata budget, pending growth, quota/available logical headroom, pending deletion and pins |
| Failure/lifecycle | Fixed-category storage failure/watchdog counters; state/readiness gauges; shutdown duration/outcome counters for complete, incomplete, interrupted, failed |

RPC latency runs from adapter entry to server terminal status/cancellation, not durable I/O
time or confirmed client receipt. Use an exactly-once metric terminal guard independent of
the broker reservation. Canceled responses and late writer/read completions must not double
count RPC outcomes or release ownership. Keep broker storage/watchdog events separate from
RPC errors; a canceled RPC may later publish durably. Benchmarks measure client-observed time.

Use fixed bounded histogram buckets, not an unbounded sample list. Register names and label
values from fixed enumerations: no topic/group/session/member labels, raw error text, paths,
payloads or user-controlled meter names. Expose no per-partition diagnostic endpoint initially.
Aggregate partition-related gauges from bounded in-memory snapshots; never scan files or
synchronously submit work to the coordinator on scrape. Add a small aggregate snapshot where
existing diagnostics would allocate full identity maps. Snapshots are point-in-time per owner,
not a globally atomic transaction across all workers; document possible transient differences.
Metric recording is nonblocking and cannot run exporter/network callbacks on coordination.

Use actual operations to test success, invalid input, overload, fencing, cancellation, late
completion, parked activation, quota/pins, injected I/O degradation and incomplete shutdown.
Assert bounded series under many identifiers. Document omitted per-partition lag, free-disk
capacity, syscall timings and any other metric that cannot be measured truthfully here.

## Examples and distribution

Add a small `clients` Gradle application module with an installable distribution, generated
from only the canonical `app/src/main/proto/kaflow/v1/broker.proto`. Reuse the pinned gRPC,
Protobuf and Java 17 versions from one build definition; no copied/handwritten generated Java
and no dependency on server storage classes or test fixtures. Use explicit entry commands
for produce, consume, two-group demo and benchmark, with bounded CLI arguments/deadlines.

Producer: serialize an order object to UTF-8 JSON outside the broker, optionally attach an
opaque key, and print qualified partition/offset/timestamp. Demonstrate absent versus empty
key without decoding arbitrary broker payloads. A timed-out/canceled/disconnected Append has
unknown outcome; report it and stop rather than silently retry. Use binary fixtures in tests.

Consumer: one bounded processing loop plus an independent single heartbeat task (one RPC at
a time). Publish the latest immutable membership view through one bounded thread-safe slot,
not an unbounded update queue. Heartbeat cadence is comfortably within the returned lease
and its deadline; processing/long polling cannot block renewal. Capture an immutable batch
identity at Fetch dispatch: original session, incarnation, generation and qualified partition
(with its group context). Associate the returned batch with that identity, never credentials
read later from the heartbeat slot. Heartbeat must not mutate an existing batch's identity.

Immediately before processing, compare the batch identity/assignment with the current
membership view and closing state. Discard fetched-but-unprocessed work on ownership or
generation change, refresh assignments and resume from broker progress. This is a check of
the latest observed membership, not a guarantee against a subsequent/unobserved reassignment.
Ownership can change during application processing; fencing cannot undo application side
effects or make processing exactly once. Commit completed work only with its batch's original
identity. Never relabel old work with a newer generation to make Commit succeed.

If Commit is fenced or ambiguous, report possible duplicate processing and recover membership
before resuming from broker-reported durable progress. Refresh a surviving session through
Heartbeat; rejoin after expiry/restart with bounded retry/backoff and an overall client
deadline. Lost Join responses can leave sessions until expiry; do not retry in a tight loop.
Use deterministic barriers for changes before processing, during processing and between
processing and Commit, checking both side effects and the exact original Commit credentials.

Fetch count/bytes/wait and all client queues are bounded. Process before committing the next
offset. Handle overload/unavailable separately from empty success; never auto-retry Append.
Report OUT_OF_RANGE with current membership range and stop by default. An explicit client
option can choose the retained start and clearly report skipped history; merely selecting a
Fetch cursor does not reset or commit progress. Replay mode uses an explicit earlier offset
and never commits. Do not log credentials. On exit stop new work, cancel pending Fetch,
boundedly drain/stop heartbeat, attempt Leave for the current session, then close the channel.
Guard membership publication with the client lifecycle/session epoch: late Heartbeat/Join
completion cannot replace a newer session or restart work after shutdown. Test heartbeat and
consumer shutdown races, cancellation/late completion, and bounded scheduler/channel teardown.

The two-group demo proves distinct committed positions, replay with unchanged progress,
restart/rejoin and explicit retention handling. Explain duplicates when a crash occurs after
processing but before Commit, and possible lost application work if committing first.

## Container and shutdown experience

Add a multi-stage Dockerfile: Java 17 JDK builder using the checked-in wrapper and a Java 17
JRE runtime. Select a concrete Temurin distribution/version and verified immutable digest
during implementation; record multi-architecture support and an explicit refresh/revalidation
policy. Do not call mutable tags reproducible. Preserve wrapper verification; do not upgrade
application dependencies incidentally. Source-build reproducibility is not a byte-identical
image claim. Review an allowlisted `.dockerignore` context; copy only the built broker and
small health-probe artifact into runtime, with no caches, credentials, tests or evidence.

Run UID/GID 10001 with an image-owned `/var/lib/kaflow` mountpoint; use a dedicated named volume
and data subdirectory. Verify initial volume ownership and non-root writes on the real engine.
For pre-existing volumes with unsuitable permissions, fail clearly and document narrowly
scoped ownership preparation; do not silently start as root or recursively change host mounts.
Use an exec-form Java entrypoint. A tiny JDK-only HTTP health probe, compiled in the builder,
checks readiness with a bounded timeout; no dependency on a host-installed curl or test class.

Compose deliberately binds inside the container to `0.0.0.0` while publishing host ports only
as `127.0.0.1:8080:8080` and `127.0.0.1:9090:9090`. No privileged mode or broad host mounts.
Use the same explicit durable directory/topic configuration as native startup. Set shutdown
timeout 10s and Compose grace 15s; document and validate that grace exceeds the application's
total budget plus a margin. Thread the configurable application budget through lifecycle and
startup-failure cleanup without creating a second wait budget. Preserve unresolved worker,
pin, barrier, callback and directory-lease ownership after an incomplete timeout.

The replacement demo records public Append receipts and two group positions outside the
container, stops/removes only the container, recreates it on the same named volume, and
verifies exact bytes/offsets/progress through V1. Also exercise SIGTERM with a proven parked
request and bounded cleanup. Keep `down -v` out of ordinary shutdown; test-owned volume removal
requires an explicit isolated test project/cleanup path and cannot target a user's data volume.
Report Docker Desktop's Linux VM separately from native Linux local storage. Actual supported
filesystem checks remain enforced; never bypass them to make a container test pass.

## Benchmark protocol

The public-gRPC harness is separate from storage tests. Use fresh provisioned durable stores,
retention disabled, the standard force/checkpoint protocol and documented quota/limits. Four
scenarios: durable Append; bounded Fetch of preseeded data; process/Fetch plus effective durable
Commit; and delivery to a proven parked Fetch. No storage shortcuts or test-only data seeding.

Initial fixed measured matrix: 256-byte payload, 16-byte key, four partitions, one measured
group, closed-loop concurrency 1 and 4 for Append/Fetch. Consumer commits are serialized per
group so the intended Commit workload does not primarily measure barrier rejections. State
that choice and effective writes versus authorized no-op commits.

For parked delivery, use an isolated benchmark broker with no competing Fetch clients and
the aggregate parked-waiter gauge through the ordinary metrics endpoint. First observe
zero parked requests, issue one long-poll Fetch at published end, then observe the gauge reach
one within a bounded setup deadline while confirming that this Fetch remains outstanding.
Recheck the outstanding state immediately before dispatching Append. Initial configuration:
2s maximum setup observation, 10s server wait (within the broker's configured limit), and an
RPC deadline longer than that wait plus the bounded delivery allowance. The wait must remain
comfortably longer than setup; reject incompatible harness configuration rather than routinely
racing wait expiry. Zero-baseline and post-attempt cleanup observations are bounded as well.

Only after registration is established start timing at Append dispatch and end at Fetch
completion with the same client monotonic clock. Keep all metrics requests and registration
observation outside that interval; report Append latency separately. Verify the delivered
record matches the trigger. A missing baseline, observation timeout, completed/expired Fetch
before dispatch or observation error is a setup failure, never a parked-delivery sample.
An unexpected empty/error response after dispatch is a delivery failure, not successful
parked latency. Cancel/drain failed setup attempts before the next attempt; no overlapping
observations or test-only public endpoint. Never subtract server timestamps or assume Fetch
delivery occurs after the Append response.

Fetch uses at most 128 records/64 KiB; preseed a fixed bounded corpus through V1 outside timing.
Record exact scenario parameters in machine-readable configuration. Run 10s warmup, 30s
measurement and three independent repetitions per scenario/configuration, reporting individual
results and variation rather than only a pooled percentile. These are initial workload choices,
not throughput targets; any changed workload gets a separate named result and explanation.

Bound workers, in-flight requests, generated corpus, verification receipts and run duration.
Every generated payload encodes a unique per-run ID and monotonically assigned record sequence,
with deterministic filler to retain exactly the configured total payload size (256 bytes in
the initial matrix). Define a fixed binary identity layout and reject payload sizes too small
to hold it; never silently enlarge the workload. Warmup/preseed and measured phases use
distinct identities and ledgers so their records and acknowledgements cannot be conflated.

Reserve bounded ledger capacity before issuing an Append, including every in-flight request
that could acknowledge. Initially allow 100,000 receipts/run, plus an explicit aggregate byte
limit sufficient for configured exact key/payload copies and metadata; validate the count/byte
combination before the run. Keep each acknowledgement linked to run/sequence, exact payload,
key presence and bytes, qualified partition, offset and broker timestamp. Absent and empty keys
remain distinct. Do not discard receipts when a cap is reached: stop issuing, drain and verify
the retained ledger, and report receipt-cap termination rather than a complete fixed run.

Use fixed logarithmic latency buckets with documented resolution/overflow and monotonic timing;
no per-request latency list. Define the measurement window as [start, cutoff) on the client
monotonic clock, draining warmup before it starts. At cutoff atomically stop issuing measured
requests and record the in-flight count. Drain existing requests under a separate bounded
deadline (initially 15s) without issuing replacements. Record success/failure/timeout/rejection
outcomes that resolve during drain separately; unresolved operations at that deadline remain
explicitly unresolved/possibly durable. Cancel outstanding client calls and perform bounded
cleanup without interpreting cancellation as proof that the broker did not mutate.

Throughput is successful completions within [start, cutoff), from requests issued in that
window, divided by its actual measured duration. Report attempted offered rate using the same
duration and issuance window, plus records/bytes per second for Fetch separately from RPCs/s.
Drain successes never increase in-window throughput; report them and total acknowledged records
separately. A cap-aborted run reports its actual truncated window and is not a completed fixed
workload. In-window latency histograms include only terminal completions in that window;
separate drain histograms use each request's original dispatch-to-completion duration. Report
success latency separately from all terminal-attempt latency, failure categories and censored
unresolved counts. No unresolved request gets a fabricated latency sample. Closed-loop results
include no claim about open-loop overload/tail behaviour or correction for coordinated omission.

Verification follows outside timing and draining: Fetch every acknowledged retained record,
including drain acknowledgements, and match its complete ledger identity, bytes/key presence,
qualified partition, offset and timestamp. Check for missing/duplicate receipt mappings and
rejoin to check durable committed progress; verify independent groups in correctness mode.
Retained records from failed/lost-response operations may also exist. Classify their run/sequence
as unacknowledged/ambiguous rather than assuming any non-success means no mutation; never count
them as acknowledged throughput. An ambiguous Commit requires progress recovery, not an assumed
rollback. Verification itself has bounded reads, memory and a deadline; unreconciled outcomes
remain reported as such. No per-record console output or assertions dominate the timed path.

Report setup, receipt-cap, quota-cap, drain and verification failures as distinct machine-readable
results and totals. A run with incomplete verification, unresolved draining or incomplete fixed
duration cannot be called a successful validated benchmark; retain partial results with its
failure/limitation status. Tests cover cutoff completions, drain successes and unresolved work,
unique payload identities at exact size, ledger reservation/caps, ambiguous persisted records,
failed registration and verification failure without weakening assertions.
Record source manifest/commit, Java/OS/CPU/memory, filesystem/device evidence (unknown if not
discoverable), native/container placement, generator resources, exact options, warmup/duration,
histogram method and limits. Treat a generator/receipt/quota cap as a limitation, not a broker
capacity result. No invented targets, production extrapolation or uncontrolled Kafka comparison.
CI runs a short finite-count correctness mode only; measured throughput is never its pass gate.

## Release documentation and compatibility

Replace the stale README landing page with the tested durable path, a small architecture
diagram and links to focused configuration, API, operations, examples and benchmark documents.
Move/relink useful prototype narrative as history without rewriting old validation observations.
Document exact commands/expected receipts and progress, health/metrics queries, quota versus
I/O failure, retention and explicit lag recovery, log/report paths and incomplete shutdown.

Verify format constants against code: current retention authority is catalog V4/checkpoint V2;
progress files remain V1 and earlier accepted catalog/checkpoint combinations are documented
with their upgrade rules. First progress and retention mutations can make old binaries unable
to reopen. No downgrade or restoration by selectively copying old metadata; disabling retention
does not restore history. Recovery failures preserve evidence and fail closed, never invite
deleting authority or truncating acknowledged data. Linux persistent local storage remains
the reference, with honored file/directory force and atomic same-directory replacement.

Document only a clean-stop backup procedure: verify complete shutdown/exclusive ownership,
copy the entire closed store, restore into a separate directory with matching permissions and
compatible binary, and verify records/progress via the public clients. Test this procedure
before calling it supported. Live-directory copying is not a consistent backup. No automated
backup service or rolling upgrade belongs to this ticket.

## Stages and validation gates

1. **Inputs and composition:** inspect usable Java 17/wrapper and Docker CLI/Compose/reachable
   engine/context/OS/architecture/location; record missing capabilities without installing or
   starting services as part of inspection. Branch/inherited manifest; typed configuration;
   isolated startup modes, loopback listeners, single shutdown budget. Test invalid input/binds.
2. **Provisioning and readiness:** idempotent ensure-topic via existing durable protocol;
   pre-listener startup gate and failure cleanup. Separate syntax validation from whole-list
   recovered-state conflict/capacity preflight. Prove a late conflict creates no earlier missing
   topic; test restart and partial I/O failure, readiness/quota/close and prototype V1 absence.
3. **Observability:** fixed meter inventory and owner snapshots; test real outcomes, races,
   cardinality and absence of coordinator I/O or resource-lifetime changes.
4. **Clients/operator path:** installable generated-stub clients; immutable original batch
   identity and bounded membership publication; deterministic changes before/during processing,
   before Commit and during shutdown. Native quickstart, two groups, replay, retention and restore.
5. **Container:** source image/Compose/non-root volume and health probe; execute replacement,
   ownership rejection, host bind and signal/parked-shutdown checks on a real Docker engine.
6. **Measurements:** correctness first: gauge-observed zero-to-one parking before trigger,
   outstanding Fetch and setup deadline, unique fixed-size payload IDs/exact receipt ledger,
   cutoff/drain accounting and complete outside-timing verification. Then repeated measured
   workloads with separate setup/cap/quota/drain/verification outcomes and honest validity flags.
7. **Release evidence:** accurate README/reference/runbooks; Linux CI and final source-only
   candidate, all inherited and new checks, exact manifest/class/package correspondence,
   final review and source-of-truth update. No publication as part of implementation.

Keep the original checks, with fresh actual XML counts (not an assumed 460):

```sh
./gradlew clean :app:classes :app:testClasses :app:bootJar --no-daemon
./gradlew :clients:installDist --no-daemon
./gradlew :app:test --rerun-tasks --no-daemon
./gradlew :clients:test --rerun-tasks --no-daemon
python3 scripts/smoke_bootjar.py
python3 scripts/smoke_durable.py
python3 scripts/smoke_retention.py
```

Planned additional entry points (to be implemented, not runnable today):

```sh
python3 scripts/smoke_release.py
python3 scripts/smoke_container.py
python3 scripts/benchmark_release.py --mode correctness
python3 scripts/benchmark_release.py --mode measured --config benchmarks/release.json
```

Each script owns only its isolated processes/ports/data/project, has bounded startup, command
and cleanup deadlines, retains logs/results on failure and exits nonzero for unmet checks.
Release smoke uses packaged production clients and broker only, no test fixtures or metadata
editing. Keep the three inherited smokes intact as independent regressions. CI adds the native
release and harness correctness checks; an explicit Docker job builds/runs the container smoke.
Both upload available reports with `if: always()` and bounded job/step timeouts. Authored CI is
not an executed run; no exact-commit verification is possible until separately authorized push.

Final evidence records all failures/fixes/reruns, counts/errors/skips/timeouts, image/digest and
source identity, commands/environment, quickstart/container/restore results, measured runs and
limitations. Review the full inherited diff and isolated KF-009 delta for correctness, secrets,
generated artifacts, unrelated edits and unsupported claims. DONE requires every required
local acceptance gate. If a required environment remains unavailable after independent work,
mark BLOCKED with the exact pending command; never substitute configuration review for execution.

The earlier planning inspection found no installed Java runtime or Docker CLI at checked
locations; retain that observation as history, not an assumption about implementation-time
availability. Perform the complete entry inspection above when implementation starts. All new
checks in this refinement are planned, not executed; no installation, service start, executable
change, build or benchmark is part of the planning refinement.

## Implemented operator surface

See [configuration](../configuration.md), [operations](../operations.md),
[clients](../examples.md) and [benchmark method](../benchmarks.md) for implemented commands.
The stages above preserve the original plan; current execution and blocked gates are in the
[validation record](../baseline/kf-009.md). Full-list preflight first checks coordinator identity/count
bounds, then checks catalog bytes, segment-header fit and managed storage headroom on the existing
single storage owner using bounded in-memory metadata, with no filesystem calls. Sequential
creation begins only after both pass. Recovery after client errors first waits for an earlier
heartbeat and issues a fresh progress observation; batches keep their original credentials.
Container source and CI are authored; their execution must be reported separately from native results.

Final native evidence:483 passing server/client tests, clean source-only package, four packaged
smokes, harness correctness and 18 measured trials. No executable input changed after the
validated manifest. Standard gRPC health/reflection remain enabled by the inherited starter
and are documented separately from broker-aware HTTP readiness. Actual Docker build/runtime
acceptance is pending; see the evidence record and exact next command in the working paper.
