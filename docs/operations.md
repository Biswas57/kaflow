# Operating Kaflow

Use the durable service explicitly with `kaflow.durable.enabled=true`, a dedicated persistent local directory, and a fixed topic list. Recovery and provisioning complete before V1 readiness; a startup failure never falls back to the volatile prototype. Only one process may own a data directory.

The supplied clients and listeners use plaintext without authentication. Session tokens fence broker ownership; they are not transport authentication. Keep default loopback examples on a trusted local machine. See [architecture](architecture.md) for ownership, data flow, API and storage formats.

## Native quickstart

With Java 17 and Python 3, run from the repository root:

```sh
./gradlew clean :app:classes :app:testClasses :app:bootJar --no-daemon
./gradlew :clients:installDist :clients:healthProbeJar --no-daemon
./gradlew :app:test :clients:test --rerun-tasks --no-daemon
java -Xms64m -Xmx512m -jar app/build/libs/app.jar \
  --kaflow.durable.enabled=true \
  --kaflow.durable.directory="$PWD/local-data" \
  '--kaflow.durable.topics[0].name=orders' \
  '--kaflow.durable.topics[0].partitions=1' \
  '--kaflow.durable.topics[1].name=json-orders' \
  '--kaflow.durable.topics[1].partitions=1'
```

Use a fresh data path and an existing parent directory. In a second terminal, check readiness and exercise both the binary demo and JSON-byte example:

```sh
curl --fail http://127.0.0.1:8080/actuator/health/readiness
clients/build/install/kaflow-client/bin/kaflow-client demo
clients/build/install/kaflow-client/bin/kaflow-client produce --topic json-orders --key-hex ''
clients/build/install/kaflow-client/bin/kaflow-client consume --topic json-orders --group json-example
```

The demo commits independent groups to 2 and 1, then verifies replay without changing progress. After a clean shutdown and restart against the same directory it resumes at 2/1 and reaches 4/2. Keep the JSON and binary examples on separate topics. Stop with Ctrl-C and wait for process exit before restart or backup.

## Single durable broker ownership

Use this [quickstart](../README.md#quickstart) and the [configuration reference](#configuration-reference).
Only one process owns a data directory. Keep the complete directory together: the durable root
marker, lock, catalog, checkpoints, progress files, log segments and indexes have related authority.
Do not edit filenames, delete an ownership marker, or manipulate metadata to bypass recovery.
A failed durable startup never falls back to an in-memory broker.

## Health and diagnostics

```sh
curl --fail http://127.0.0.1:8080/actuator/health/liveness
curl --fail http://127.0.0.1:8080/actuator/health/readiness
curl --fail http://127.0.0.1:8080/actuator/metrics
curl --fail http://127.0.0.1:8080/actuator/metrics/kaflow.wait.parked
curl --fail 'http://127.0.0.1:8080/actuator/metrics/kaflow.rpc.outcomes?tag=method:Append&tag=status:OK'
```

Liveness indicates a responsive application lifecycle, not writable storage. Readiness requires
application startup, the gRPC listener, completed recovery/provisioning, accepting broker state,
and no latched storage degradation or closing state. It returns 503 otherwise. During early
startup or after HTTP stops there may be no reachable endpoint at all. An ordinary quota or
admission rejection leaves readiness healthy: capacity exhaustion is not evidence of corrupt
storage. Disk failures and watchdog expiry latch degradation; a late successful write does not
silently restore readiness. No disk probe, directory scan or fsync runs during a health scrape.

HTTP exposes only Actuator health and metrics by default; health details are hidden. No
Prometheus registry, reset endpoint or unbounded per-topic diagnostic collection is supplied.
The inherited gRPC starter also enables standard health and reflection by default; these
are configurable separately. Standard gRPC health does not establish writable broker state
and its Watch streams are outside V1 admission accounting. Use HTTP readiness for deployment
checks. Reflection discovers schemas; it does not add topic/reset administration.
The endpoint and gRPC are plaintext with no authentication in these examples. Restrict network
access independently before deliberate remote exposure; this ticket does not supply that security.

Metrics have fixed names and labels. They sample immutable/volatile in-memory views and bounded
executor counters; related gauges may come from different instants and must not be treated as
one transactional snapshot. No metric contains arbitrary topic, group, session, error text or
payload labels. Identity never becomes a metric name.

| Metric family (`kaflow.` prefix) | Kind and interpretation |
| --- | --- |
| `rpc.outcomes`, `rpc.duration` | Counters and timers for six fixed RPC method names × gRPC status codes; one terminal server observation per call |
| `admission.calls/bytes`, `queue.mutations`, `writer.active` | Mutation/admin ownership; active writer includes metadata/maintenance work |
| `read.admitted/bytes/active`, `queue.readers` | Reserved reads versus actual readers |
| `progress.admitted/bytes/barriers` | Durable progress work and unresolved commit/reassignment barriers |
| `wait.admitted/bytes/parked/timers/notifications` | Bounded long-poll registration, retained metadata and ready notifications |
| `groups`, `sessions`, `control.admitted/bytes`, `queue.controls`, `joins.pending` | Durable definitions and live membership/control work |
| `queue.writer/responses/transport`, `responses.active`, `transport.active` | Bounded queue occupancy and response/transport workers |
| `storage.data.bytes`, `storage.metadata.bytes` | Managed data/index accounting and fixed metadata/headroom allowance, not physical disk usage |
| `storage.reserved.bytes`, `storage.quota.bytes`, `storage.available.bytes` | Reserved growth, configured quota and remaining logical budget |
| `storage.pending.deletion`, `storage.pins` | Pending deletion count and actual held segment pins |
| `storage.failures`, `watchdog.events` | Monotonic counters for unsafe storage completions and watchdog events, including late completions |
| `state`, `accepting` | One-hot five-state gauges; accepting reflects readiness of the broker core |
| `shutdown.outcomes`, `shutdown.duration` | First lifecycle close observation, fixed complete/incomplete/interrupted/failed label |

Timers have count/total/max and fixed SLO boundaries at 1ms,10ms,100ms,1s,10s,30s. The default
Actuator JSON endpoint does not provide exported benchmark percentiles; the benchmark has its
own bounded histogram. Server outcomes describe response/cancellation observation, not proof
that a client received the response. Cancellation/deadline can race server completion. Fencing
appears as FAILED_PRECONDITION; no per-session labels are needed. Metrics reset at restart.
Shutdown metrics may be unavailable over HTTP once it stops; preserve process logs/exit status.
OS physical disk, per-partition lag and fsync breakdowns are deliberately not fabricated.

## Retention, quota and recovery

Retention defaults off. When enabled, the target is per-partition managed log bytes. Only whole
eligible closed segments expire; active and pinned segments can keep usage above the target.
Durable retained-start publication precedes deletion. Active readers pin segments until actual
read/delivery unwind, not merely until a client timeout. There is no slow-consumer protection:
a group's durable progress can fall below retained start. Fetch then reports OUT_OF_RANGE.
Choose explicitly whether to restore from an independent archive or skip missing history with
the example client's `--accept-retained-start true`. Disabling retention cannot restore history.

RESOURCE_EXHAUSTED means a configured capacity or request budget was insufficient. Inspect
admission/read/wait gauges and storage quota/headroom, reduce load or choose viable limits on
restart. Increasing quota does not create free filesystem capacity. Do not delete segment files
manually. Out-of-range replay and too-small Fetch byte budgets require client choices rather
than endless retries. Failed/unknown Append or Commit responses may still correspond to durable
mutations; consult recovered state/application identities before retrying.

On DATA_LOSS, unsupported format, ownership conflict or failed recovery: preserve the full
closed store and logs, stop competing owners, and investigate the error before restarting.
Corrupt acknowledged retained data is not silently discarded. Recovery truncates only state
outside durable publication authority and cleans recognized unpublished artifacts. It does not
repair arbitrary user modifications. There is no automatic repair/downgrade command.

Current formats are catalog V1–V4 (V4 carries retention authority), checkpoint V1/V2 (V2 retained
start), progress V1 and partition-log V1. New stores can start with older catalog framing until
features require upgrade. Group progress upgrades catalog authority to V3; retention upgrades
to V4 with V2 checkpoints. Recovery understands established older formats and interrupted
upgrades. Treat any attempted upgrade as a rollback boundary: **no supported downgrade** to
older binaries after incompatible metadata publication begins. Preserve an independently
verified closed-store backup before upgrading. Merely disabling the newer feature is not rollback.

## Shutdown and closed-store backup

SIGTERM/Ctrl-C closes admission and readiness, stops transport, wakes parked requests, and waits
for actual writer/read/response/transport cleanup under one total budget (default 10s). Normal
shutdown releases ownership only after real work unwinds. `Durable shutdown incomplete` or
`Durable shutdown failed` is a failure report, not successful release. An outstanding syscall
may outlive that budget; the broker must retain directory ownership, and a supervisor may need
to terminate the process. Never start a replacement while the owner still runs. A forced kill
requires conservative recovery and can leave client-observed writes ambiguous.

To back up: stop the broker, confirm complete shutdown and process exit, exclude other owners,
then copy **the entire directory** to a separate location. For example, with stopped `local-data`,
`cp -R local-data backup-data` on the same trusted host. Do not copy a live store as a consistent
backup. Restore into a new, absent path with `cp -R backup-data restored-data`; start explicitly
against `restored-data`, preserving the same provisioning, quota and retention policy. Verify
known records and group progress through V1. Keep the original unchanged until verification.
The native release smoke performs this closed-copy restore, matching prior acknowledgements and
independent group progress. That verifies this procedure on the tested filesystem, not every
backup utility or cross-filesystem migration.

## Container ownership and platform assumptions

The image pins official Temurin Java 17 Jammy JDK/JRE manifest-list digests in the Dockerfile.
The Gradle 8.5 distribution has a checked SHA256. Dependency versions are explicit and protobuf
is generated from source. This is a reproducible source workflow, not a claim of byte-identical
images: dependency repositories and build timestamps remain external inputs. Refresh image
digests deliberately for security updates, inspect platform manifests, then rerun native and
container checks. Nothing in the local task publishes an image or release.

Runtime UID/GID 10001 owns `/var/lib/kaflow`; a fresh named volume receives that ownership.
Keep it a dedicated volume. Existing incorrectly owned volumes are not silently chmod/chowned
by a privileged helper; correct them through an intentional offline operator procedure.
The JVM is PID1 via exec-form ENTRYPOINT. The healthcheck is a small JDK-only readiness probe;
there is no curl/package-manager dependency in the runtime stage. Only application and health
JARs are copied; source, tests, caches and evidence remain outside the runtime image.

Compose explicitly publishes loopback host ports, drops capabilities and uses no privileged
mode or broad host mounts. `KAFLOW_HTTP_PORT`/`KAFLOW_GRPC_PORT` change host ports. Container
addresses are wildcard for container networking; this does not justify wildcard host publishing.
Keep stop grace above the configured total broker shutdown budget. Ordinary `docker compose down`
preserves the named volume. The acceptance script alone removes its own uniquely named test
project's volume; it never reuses the ordinary demo project.

Linux persistent local filesystems honoring file/directory force and atomic replacement are the
reference durability environment. macOS/APFS is a development validation environment. Docker
Desktop uses a Linux VM; bind mounts, remote filesystems, overlay/VM and storage caches have
additional behavior requiring validation. Process SIGKILL/restart checks are not physical
power-loss tests. No replication protects against host or disk loss. Run the container gate
after the client distribution is built:

```sh
python3 scripts/smoke_container.py
```


---

## Configuration reference

All broker options are read once at startup; changes require a clean restart. The supported
quickstart precedence is command-line `--property=value` over environment over external
`application.properties`/YAML over packaged defaults. Java system properties also outrank
environment. An external `application-{profile}` file overrides its corresponding base file;
profile ordering and `spring.config.*` are Spring Boot configuration, not a second broker
configuration mechanism. Prefer one explicit external file and CLI overrides. Packaged smoke
checks exercise external-file → environment → CLI precedence using actual storage-quota metrics.

Use canonical kebab-case property names. Environment uses uppercase, dots to underscores,
and removes hyphens: `kaflow.durable.max-storage-bytes` becomes
`KAFLOW_DURABLE_MAXSTORAGEBYTES`; `kaflow.durable.wait.max-requests` becomes
`KAFLOW_DURABLE_WAIT_MAXREQUESTS`. Lists use an underscored index, e.g.
`KAFLOW_DURABLE_TOPICS_0_NAME`. CLI list keys must be quoted in shells such as zsh.
No wildcard environment export is needed. Unknown fields under `kaflow.durable` fail binding.

## Modes and listeners

| Property | Default | Supported meaning |
| --- | --- | --- |
| `server.address` / `server.port` | `127.0.0.1` / `8080` | HTTP diagnostics; port 1–65535 (0 for ephemeral testing) |
| `grpc.server.address` / `grpc.server.port` | `127.0.0.1` / `9090` | gRPC; port 1–65535 (0 for ephemeral testing) |
| `grpc.server.health-service-enabled` | `true` | Inherited standard gRPC health; not broker readiness |
| `grpc.server.reflection-service-enabled` | `true` | Inherited standard gRPC reflection; schemas/service discovery |
| `kaflow.durable.enabled` | `false` | Explicit `true` selects durable V1; false selects volatile prototype |
| `kaflow.durable.directory` | none | Required, nonblank dedicated writable directory in durable mode |
| `kaflow.durable.topics[i].name` | empty list | Exact nonblank identifier, valid UTF-8, ≤255 encoded bytes, no duplicates |
| `kaflow.durable.topics[i].partitions` | required per entry | 1..configured partitions/topic; fixed once created |
| `kaflow.durable.shutdown-timeout` | `10s` | Duration >0 and ≤5m; shared total broker/transport teardown budget |

Durable mode exposes the `kaflow.v1.Broker` application API plus health/metrics HTTP.
The gRPC starter also enables standard health and reflection services by default in both modes;
set their properties above to `false` to disable them. Standard gRPC health is not connected
to broker degradation; use the documented HTTP readiness endpoint for admission/readiness checks. Legacy `/api/**`,
prototype gRPC and prototype state beans are absent. Default prototype mode retains those
legacy interfaces and does not register V1. The standalone prototype CLI/server remain historical
entry points. Neither mode supplies authentication. The release examples configure no TLS;
changing bind addresses does not add access control. There is no public reset/admin mutation API.

## Durable limits

Every suffix below is prefixed by `kaflow.durable.`. Bytes are decimal integer byte counts;
KiB/MiB/GiB in this table describe binary multiples, not accepted numeric suffixes. Counts
bound zero-byte operations too. Duration properties accept `ms`, `s`, `m` or ISO-8601 durations.

| Suffix | Default | Valid range / interaction |
| --- | ---: | --- |
| `segment-bytes` | 16777216 | 69..134217728; topic header plus record must fit |
| `max-segments` | 1024 | 1..1024 per partition; no unbounded segment inventory |
| `retention-bytes` | 0 | 0 disables; otherwise ≤max-partition-bytes, whole closed segments |
| `max-storage-bytes` | 8589934592 | 1..17592186044416; must fit metadata and rotation headroom |
| `max-partition-bytes` | 1073741824 | 1..max-storage-bytes; must fit rotation envelope |
| `storage.max-key-bytes` | 16384 | ≥0; key+payload configured maxima ≤8388608 |
| `storage.max-payload-bytes` | 1048576 | ≥0; combined limit above; segment can impose a smaller frame bound |
| `storage.index-interval` | 128 | 1..4096 records per sparse entry |
| `storage.max-read-records` | 128 | 1..128 per response |
| `storage.max-read-bytes` | 2097152 | 0..2097152 key+payload bytes per response |
| `storage.zero-progress-limit` | 8 | 1..1024 repeated zero-byte file operations |
| `broker.max-topics` | 32 | 1..128 |
| `broker.max-partitions-per-topic` | 16 | 1..64 |
| `broker.max-partitions` | 64 | 1..128 total |
| `broker.max-calls` | 128 | 1..1024 admitted mutation/admin calls |
| `broker.max-bytes` | 16777216 | 0..67108864 admitted key+payload bytes |
| `broker.watchdog` | `30s` | >0..1h; unresolved storage/read timeout degrades, not cancellation proof |
| `groups.max-groups` | 128 | 1..256 durable definitions |
| `groups.max-members-per-group` | 32 | 1..64 |
| `groups.max-members` | 256 | 1..1024 overall |
| `groups.max-pending-joins` | 32 | 1..128 |
| `groups.max-controls` | 128 | 1..512 admitted controls |
| `groups.max-control-bytes` | 2097152 | 0..8388608 logical control reservations |
| `groups.max-catalog-bytes` | 131072 | 48..131072; names and definitions must actually fit |
| `groups.lease` | `30s` | 1ms..5m, integral milliseconds; measured from application, not receipt |
| `consumer.read-workers` | 2 | 1..4 |
| `consumer.max-reads` | 16 | 1..128 admitted reads |
| `consumer.max-read-charge` | 67108864 | 0..268435456 logical read budget |
| `consumer.max-progress-calls` | 64 | 1..256 |
| `consumer.max-progress-charge` | 1048576 | 0..8388608 logical progress budget |
| `wait.max-requests` | 256 | 1..1024 |
| `wait.max-per-session` | 16 | 1..max-requests |
| `wait.max-per-partition` | 64 | 1..max-requests |
| `wait.max-metadata-bytes` | 524288 | 0..2097152; 2048 charged per waiter |
| `wait.max-wait-millis` | 30000 | 1..120000; requests may select zero for immediate Fetch |
| `wait.batch` | 8 | 1..32 bounded activation batch |

These are logical resource bounds, not total JVM heap, filesystem free-space or kernel-buffer
limits. Transport has four worker threads and a 256-entry queue. Response executors are bounded
by admission counts. Inbound V1 message size is 1024 + configured maximum key and payload bytes.
Read response metadata also consumes admission charge. Parked Fetch calls retain bounded request
metadata but no read worker, open read channel or read snapshot. Reservations remain until real
reads/writes and response delivery unwind, including after client cancellation.

Startup syntax checks precede directory open. After recovery, the complete provisioning list
is checked for conflicts, total topic/partition bounds, catalog size, segment identity fit and
available managed storage before any missing topic is created. The storage-owner preflight
uses bounded in-memory metadata, not disk scans. Creation then uses normal durable publication
one topic at a time; an I/O failure can leave a durable prefix. Restarting with the same list
completes it idempotently. Strict internal create still rejects an existing topic. Provisioning
is not a transaction. The broker remains unready on any startup failure.

Quota validation reserves a fixed metadata/index/replacement allowance derived from configured
maxima, plus a record/rotation envelope. Very small quotas may fail even on an empty directory.
Lowering limits below recovered data is an error, not permission to discard acknowledged data.
Retention cannot guarantee immediate quota relief while segments are active or pinned.


---

## Public V1 examples and client behavior

Build with `./gradlew :clients:installDist --no-daemon`. The executable is
`clients/build/install/kaflow-client/bin/kaflow-client`. It generates stubs from the canonical
[V1 schema](../app/src/main/proto/kaflow/v1/broker.proto), uses Java 17 and plaintext gRPC.
Follow the [quickstart](../README.md) first. Every option takes a value, including booleans.
The default target is `127.0.0.1:9090`; `--host`, `--port` and `--deadline-ms` override it.
The default per-RPC deadline is 15000ms (1..120000); it must exceed the selected Fetch wait.

```sh
clients/build/install/kaflow-client/bin/kaflow-client produce --topic json-orders --count 2 --key-hex ''
clients/build/install/kaflow-client/bin/kaflow-client consume --topic json-orders --group billing --count 2
clients/build/install/kaflow-client/bin/kaflow-client consume --topic json-orders --group audit --count 1
clients/build/install/kaflow-client/bin/kaflow-client consume --topic json-orders --group billing --count 1 --replay-from 0
```

The producer creates application `Order(orderId="o-42", quantity=2)` data, serializes JSON
as UTF-8 bytes, then sets Protobuf `bytes payload`. The envelope does not prescribe JSON.
The broker retains exactly those bytes; Fetch returns them for application JSON deserialization.
Omitting `--key-hex` means absent; `--key-hex ''` is present-empty. Arbitrary binary keys are
hex, e.g. `00ff80c328`. `--partition 0` takes precedence over keyed routing. Output is actual
qualified partition, offset and broker timestamp. There is no automatic Append retry. A timeout,
lost response or INTERNAL/UNAVAILABLE outcome can follow a durable mutation; retrying may duplicate.
Application identity/deduplication is the application's responsibility.

The consumer joins one topic/group, uses broker-reported committed offsets, and heartbeats on
an independent single thread even while Fetch or application work blocks. Default Fetch is
128 records / 65536 key+payload bytes / 1000ms wait. Options are `--max-records` (1..128),
`--max-bytes` (0..2097152), `--wait-ms` (0..120000, also bounded by server), `--count` (1..100000),
`--seconds` (1..3600, default 30). `--format bytes` accepts opaque binary application data;
`--format json` expects the example's order fields. `--process-delay-ms` (0..60000) demonstrates
independent heartbeat. The loop stops starting work at its time budget; an in-flight RPC may
finish at its RPC deadline, followed by bounded cleanup. Application work checks its budget
between records; partial effects before a timeout can repeat.

Each batch retains immutable original session/incarnation/generation and qualified partition.
A fetched batch that no longer matches the latest received membership is discarded before processing.
That local observation cannot atomically prove that the server still grants ownership.
Reassignment can still happen during application processing; fencing cannot undo those effects.
Commit always uses the batch's original credentials, never a newer generation. Fenced or
ambiguous results report possible duplicate processing; recovery waits for any earlier heartbeat,
requests fresh membership/progress, and rejoins only if the session expired. Ordinary overload
backs off under the overall loop budget. Fetch alone and `--replay-from` never Commit.

Processing before Commit permits duplicate application work after a crash. Committing before
processing would risk losing application work. A successful Commit only confirms a durable next
offset, not application effects. There is no automatic transaction with an external database.

A lagging committed offset below retained start raises OUT_OF_RANGE and stops by default.
After deciding to skip unavailable history explicitly, run the same command with
`--accept-retained-start true`; it logs that decision before reading from the retained bound.
This does not restore deleted data or silently rewrite a group. Subsequent processed records
may be committed normally. There is no supported public inactive-group reset command.

On close the client cancels its outstanding Fetch, shuts down the heartbeat (5s wait), attempts
Leave within the configured RPC deadline, then closes its channel (5s wait). Late membership
responses cannot resurrect it. Failure to leave does not make a session permanent: leases expire.
Cleanup is bounded but does not cancel already-applied durable server work.

`demo` uses a dedicated fresh one-partition `orders` topic and groups `demo-a`/`demo-b`.
It appends two four-byte binary records with present-empty keys, commits two for A and one for B,
checks exact bytes and receipt metadata, then replays without advancing progress. Run it once,
restart the same store, and run it again: start 0/0 → commit 2/1, then start 2/1 → commit 4/2.
Do not mix JSON records into that binary topic; use `json-orders`. Demo inspection is bounded to
128 records, so use a fresh store for a new independent demo rather than running it indefinitely.


---

### Packaged release checks

Build server/classes and clients before these checks. Each script writes a bounded JSON
result and logs under its own ignored `build/reports` directory.

```sh
python3 scripts/smoke_bootjar.py
python3 scripts/smoke_durable.py
python3 scripts/smoke_retention.py
python3 scripts/smoke_release.py
python3 scripts/smoke_container.py
```

The prototype smoke checks startup, REST administration, HTTP health, the gRPC listener,
SIGTERM, and closed ports. The durable smoke checks real Append receipts, two independent
groups, crash/restart recovery, and a proven parked Fetch. Retention smoke checks retained
start, read pins, parked work, and restart. Release smoke checks the packaged public client,
listener addresses, config precedence, provisioning rejection, replay, and closed-copy
restore. Container smoke adds non-root volume ownership, persistent replacement, and parked
request shutdown. These process checks do not establish physical power-loss durability.

## Reproducing measured workloads

Build the broker and clients using the [quickstart](../README.md), then run these separately
from functional tests or other load:

```sh
python3 scripts/benchmark_release.py --mode correctness
python3 scripts/benchmark_release.py --mode measured --config benchmarks/release.json
```

Correctness uses eight operations per scenario and includes deliberate registration-setup and
receipt-cap failures. It validates the harness; its throughput is not a performance result.
Measured mode creates a fresh native broker/store per trial, runs 10s warmup and 30s measurement,
and repeats each of six workloads three times. [Configuration](../benchmarks/release.json) fixes
256-byte payloads, 16-byte keys, four partitions and retention disabled; default server limits
apply. Client and server each use `-Xms64m -Xmx512m`, run on the same host, and may contend.
The source manifest, environment, logs, exact outcome counts, histograms, receipt ledger and
verification results are retained under `app/build/reports/benchmarks/{mode}/run-*`.

| Workload | Concurrency | Measured operation |
| --- | --- | --- |
| Append | 1 and 4 | One public unary Append through the normal force/publication protocol |
| Fetch | 1 and 4 | Immediate public Fetch, max 128 records/65536 key+payload bytes; preseed 128/partition outside timing |
| Consumer | 1 | One durable Append + one-record Fetch/metadata touch + durable Commit cycle; not pure Commit throughput |
| Long-poll | 1 | One isolated proven-parked Fetch, triggered by Append; cycle throughput and delivery latency are distinct |

Fetch repeatedly starts at offset zero in each partition; this is a warm retained-page workload,
not a scan of an ever-growing corpus. The JSON field `successfulRpcsPerSecond` counts measured
operations: a consumer cycle contains three RPCs and a long-poll cycle contains two. It does
not count every heartbeat or setup RPC.

Each Fetch worker has its own group (one or four per phase); consumer and long-poll use one
group per phase. Append has no consumer group. Warmup and measurement use separate topics/groups;
verification adds its own bounded groups after timing. All server limits are the configuration
reference defaults in the supplied runner.

Loads are closed-loop: one outstanding measured operation per worker, no independent open-loop
arrival schedule. Report actual issued operations / window seconds as offered load. Server
backpressure, the loopback client, serialization, fsync and shared-host scheduling can all limit
these rates. There is no claim of production capacity or comparison with Kafka.

Each payload includes a unique run UUID, sequence and warmup/measurement phase in 29 bytes,
with deterministic filler up to exactly the configured payload size. The bounded receipt ledger
reserves before dispatch, including in-flight Append work: at most 100000 entries and 128MiB by
default (hard byte ceiling 256MiB), charged512 + twice key/payload length per entry. It retains
expected bytes, key presence, qualified partition, offset and timestamp. No per-record logging
or disk verification runs in the timed interval. Caller/transport/JVM memory is additional.

For parked delivery, an isolated broker's ordinary aggregate parked gauge must begin at zero.
The harness issues exactly one Fetch, observes parked=1 within 2s, verifies it remains outstanding,
then dispatches Append. Server wait is 10s; RPC deadline 15s. The delivery interval starts at Append
dispatch and ends at Fetch completion; Append latency is also reported separately. Gauge probes
are outside timing. A missing registration is a SETUP failure, never a parked-delivery sample.
There is no test-only endpoint. Setup and cleanup still affect cycle throughput.

Measurement stops issuance at the cutoff. In-flight calls drain for a separate 15s deadline.
In-window OK throughput counts only completions observed by the cutoff, divided by actual window
duration; late successes do not inflate it. Drain outcomes and latencies retain original dispatch
time and are reported separately. Attempted, successful, rejected/status-coded failures and
unresolved/censored counts are explicit; failed/timeout Append may still have persisted. All-status
and successful-only latency histograms are separate. Setup failures have no operation latency.
A prematurely aborted window is labeled; it is not a successful fixed-duration run.

Latency uses 1024 fixed logarithmic histogram buckets, 16 subdivisions per power of two. p50/p95/p99
are bucket upper bounds (≤6.25% relative quantization plus1ns), with exact count/min/max/mean.
This bounds memory without sampling away failures. Timer resolution, GC and scheduling remain
measurement limitations. Successful-only latency is not the latency of the whole workload.

Outside timing, public Fetch checks every acknowledged record against its exact expected bytes,
key presence/key, qualified identity/offset/timestamp. Other persisted run records are counted
separately from acknowledgements. Rejoined consumer groups verify durable committed progress.
Verification is bounded by the ledger, per-page Fetch and a120s deadline. Receipt-cap, quota-cap,
setup, operation, drain and verification failures remain in the report. Unresolved draining or
incomplete verification sets `validated=false` and exits unsuccessfully; errors are not dropped.

CI runs only short correctness. Repeated measured workloads are local evidence, not a CI
throughput gate. Actual final-source measurements and environment are recorded in
[KF-009 validation](history/implementation/kf-009.md); no result should be inferred from this method alone.
