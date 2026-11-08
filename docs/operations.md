# Operating a single durable broker

Use the explicit durable [quickstart](../README.md) and [configuration reference](configuration.md).
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
power-loss tests. No replication protects against host or disk loss. The current environment
has no Docker CLI/Compose/engine; build, UID/volume, replacement and signal checks remain pending:

```sh
python3 scripts/smoke_container.py
```
