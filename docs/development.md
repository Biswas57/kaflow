# Build and verify Kaflow

The [KF-002 contract](design/kf-002-broker-contracts.md) separates the injected
prototype runtime from the in-process V1 core. Default startup keeps the V1 service
unregistered. The ordinary packaged smoke checks that prototype; focused tests
establish core behaviour.

The [KF-003 storage design](design/kf-003-partition-log.md) specifies a file-backed
partition component whose completed writes alone are not durable acknowledgements.
KF-004 adds a separate durable runtime around it, with explicit force/publication
ordering and recovery. It shares no state with either volatile namespace.

The default application is an in-memory prototype. A successful build or health
check does not establish durable acknowledgements, correct delivery/offset
semantics, Kafka compatibility, or performance. The active plan is [../todo.md](../todo.md);
old README/blog test-count and coverage claims are not baseline evidence.

The [KF-004 design](design/kf-004-durable-publication.md) defines opt-in durable
Append and its recovery/filesystem requirements.

The [KF-005 design](design/kf-005-leased-groups.md) specifies durable one-topic group
definitions and ephemeral leased membership. The [KF-006 design](design/kf-006-fetch-commit.md)
adds immediate Fetch, durable Commit, independent replay and internal inactive reset.

## Prerequisites

- JDK **17**, including `java` and `javac`, on macOS or Linux.
- Use the checked-in **Gradle 8.5 wrapper**, not a system Gradle installation.
- Python 3 for the portable smoke check and optional bounded command runner below.
- Network access to the Gradle distribution, Gradle Plugin Portal, Maven Central,
  and their download redirects on the first build.
- Loopback sockets permitted. Integration tests select ephemeral ports and own their
  server/channel teardown; no fixed test port needs to be freed.

On macOS, select an installed JDK 17 for this shell:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export PATH="$JAVA_HOME/bin:$PATH"
```

On Linux, install JDK 17 with the distribution's supported package manager (for
example `openjdk-17-jdk` on Ubuntu), then set `JAVA_HOME` to its actual installation
directory. On either platform, a verified vendor JDK archive can be extracted to
a private directory and selected with `JAVA_HOME` without changing system defaults.
For macOS archives the JDK home is typically `<extracted-jdk>/Contents/Home`.
See [Temurin archive installation](https://adoptium.net/installation/archives/).

From the repository root, verify what will actually run:

```sh
uname -sm
java -version
"$JAVA_HOME/bin/javac" -version
./gradlew --version --no-daemon
git branch --show-current
git rev-parse HEAD
git status --short
```

Missing Java, wrapper-download failure, dependency/plugin-resolution failure,
compilation failure, packaging failure, test failure, and startup failure are
different results. Record the first failed stage and exit status; do not call an
unreached stage passing or failing. On a managed network, Java may lack an
organization CA that macOS already trusts. Use the approved CA and a local Java
trust store if needed; never disable certificate checking. KF-001's environment
adjustment is recorded in [baseline/kf-001.md](baseline/kf-001.md).

## Compile and package, then test

```sh
./gradlew clean :app:classes :app:testClasses :app:bootJar --no-daemon
./gradlew :app:test --rerun-tasks --no-daemon
```

Run these separately and retain each exit status. The first command generates
messages and gRPC stubs from the schemas under `app/src/main/proto/` (prototype and
V1 Append), compiles production
and test source, and creates the executable Spring Boot JAR in `app/build/libs/`.
The second command reruns the full test suite; it also reruns task prerequisites.
Neither command invokes the separate `coverage` threshold task.

Generated Java, compiled classes, and reports belong in ignored build directories.
Do not edit or commit `app/build/` or `app/bin/`. Keep the schema, wrapper scripts,
wrapper properties, and wrapper JAR in version control.

Reports from the test command:

- HTML: `app/build/reports/tests/test/index.html`
- JUnit XML: `app/build/test-results/test/TEST-*.xml`
- Smoke: `app/build/reports/smoke/application.log` and `result.json`

The source inventory at KF-001 is seven active test methods in three classes:
`SimpleOffsetTest` (2), `OffsetManagementTest` (2), and
`TributaryStreamIntegrationTest` (3). Six other test files are entirely commented
out. KF-002 preserves those seven and adds core, schema, ownership and network-boundary
tests: the [KF-002 validation record](baseline/kf-002.md) reports 40 executed tests
across ten classes. KF-003 preserves those 40 and adds 80 storage test invocations:
the [KF-003 validation record](baseline/kf-003.md) reports 120 passing tests across
16 classes, including parameterized corruption cases and separate-JVM ownership.
KF-004 preserves those 120 and adds 71 durable test invocations: the
[KF-004 validation record](baseline/kf-004.md) reports 191 passing tests in 21 classes,
plus both packaged smokes on the final source after root-marker/orphan-cleanup review.
KF-005 adds 57 membership/metadata/control/network/schema invocations: its
[validation record](baseline/kf-005.md) reports 248 passing tests in 27 classes and both
existing packaged smokes on final source. No KF-005 CI execution is claimed.
Inventory is not execution evidence or coverage. Derive outcomes
from fresh XML reports; distinguish setup, test-body, teardown, discovery, and timeout errors.

For an explicit local timeout on both macOS and Linux, use this standard-library
runner, changing `command` for the build or test command as needed. It propagates
failure and reports timeout as exit 124. Run it separately for each command;
save its stdout/stderr to a log if retaining evidence.

```sh
python3 - <<'PY'
import os, signal, subprocess, sys
command = ['./gradlew', ':app:test', '--rerun-tasks', '--no-daemon']
process = subprocess.Popen(command, start_new_session=True)
try:
    status = process.wait(timeout=600)
except subprocess.TimeoutExpired:
    print('TIMEOUT after 600 seconds; terminating this command group', flush=True)
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()
    status = 124
sys.exit(status)
PY
```

## Verify packaged startup and shutdown

After `bootJar` succeeds, run:

```sh
python3 scripts/smoke_bootjar.py
```

The script finds the JAR by its `Start-Class` manifest entry rather than assuming
a filename. If multiple executable JARs exist, run the clean build or pass the
specific path with `--jar app/build/libs/<actual-filename>.jar`.

It reserves two different free ports, releases them immediately before launching
`java -jar`, and explicitly sets both HTTP and gRPC addresses to `127.0.0.1`.
A competing bind can still race with launch; a conflict fails the check and is
retained in the application log. It waits up to 60 seconds for HTTP health `UP`
and a TCP connection to the gRPC listener, then creates and reads a temporary
in-memory topic through `/api/topics`. This does not exercise streaming delivery.

Finally it sends SIGTERM to its child, allows 20 seconds to exit, records the
actual process status, and checks that both selected ports refuse connections.
HotSpot commonly exits 143 for SIGTERM; that alone is not a shutdown defect.
If the child hangs, the script records failure and kills only that child. The
JSON result contains the selected ports, command, PID, health/admin results,
timeouts, exit status, and closed-listener checks. Inspect the application log for
startup/shutdown errors too. The command exits nonzero on a failed check.

Health and listener checks are startup evidence only. They do not test subscription
cleanup, durability, message delivery, crash recovery, or physical power loss.

## Opt-in durable Append and restart smoke

Use an explicitly selected persistent local directory with an existing parent:

```sh
java -jar app/build/libs/app.jar --kaflow.durable.enabled=true \
  --kaflow.durable.directory=/absolute/path/to/kaflow-data
```

Successful recovery is required before V1 service registration. Initialization failure
aborts startup; there is no volatile fallback. Append, Join, Heartbeat, Leave, Fetch and
Commit are exposed. Durable topics
are created through the bounded internal `DurableBroker.createTopic` composition API;
legacy REST administration does not create durable topics. No public inspection or reset
endpoint exists. The fixture below seeds its own temporary topic internally.

Durable mode uses a dedicated storage writer, coordinator, two storage-response workers,
two membership-response workers, two consumer-response workers, a bounded read pool (two
workers by default), and four transport workers with a bounded queue. Inbound message size and application
admission have separate bounds; transport buffers precede admission. `/actuator/health`
becomes OUT_OF_SERVICE when durable readiness degrades. A watchdog stops mutation
admission and retains the writer/directory ownership; explicit close and recovery are
required. A timed-out shutdown reports incomplete cleanup, without interrupting disk I/O.

Join durably creates an exact group/topic binding if absent. Every Join allocates a fresh
session, even with a duplicate label: lost-response retries can leave sessions until expiry.
Keep the returned incarnation/session credentials private. Heartbeat renews a live session
and returns its latest generation and qualified assignment without requiring the caller's
old generation. The default lease is 30 seconds; renew well before it elapses. Expired or
previous-incarnation credentials are fenced. Leave removes only its matching live session.

GroupOptions defaults bound 128 groups, 32 members per group, 256 total members, 32 pending
Joins, 128 controls, 2 MiB admitted control bytes and 128 KiB catalog metadata. Constructors
can select lower limits within documented hard ceilings. Groups are durable; sessions and
assignments must be re-established after restart. Independent control admission lets live
members heartbeat during disk stalls; successful Heartbeat does not clear degraded readiness.
V1 catalogs open unchanged with no groups. Creating the first group writes catalog V2,
which old KF-004 binaries reject. First effective Commit/reset installs catalog V3 and
required per-group progress vectors, which older KF-005 binaries reject.

After the source build and test compilation, run:

```sh
python3 scripts/smoke_durable.py
```

This standard-library Python driver extracts production classes/dependencies from the
actual executable JAR and runs a separate Java test fixture. It creates a private data
directory, starts the packaged Spring application, receives real Append responses in
an external client and forces its receipt witness. Two independent groups Fetch/Commit
and verify replay without progress changes. It then SIGKILLs that child, restarts from
the same data, appends at recovered next offsets, rejoins/resumes each group at its own
committed position, shuts down normally and verifies
all four exact records through the bounded internal fixture. No public API is added for
inspection. Startup is bounded at 60 seconds, client commands at 30 seconds, shutdown at
20 seconds (five-second forced cleanup). Logs, receipt witness, small data directory and
result JSON are retained under `app/build/reports/durable-smoke/`; extraction scratch is
removed. A successful run is process-crash/restart evidence, not physical power-loss proof.

Linux local storage with correctly honored file/directory flushes is the durability
reference. macOS/APFS is the development environment. Known remote/memory-only and
unsupported filesystems fail rather than downgrade. Device caches, filesystem semantics,
uncertain failed-write outcomes and explicit KF-003 compatibility policy are documented
in the design. Standalone KF-003 directories without publication metadata are not migrated.

## Source-only verification and CI

A fresh checkout excludes ignored artifacts. When verifying **uncommitted** changes,
`git archive HEAD` is insufficient because it excludes those changes. Copy the
current tracked files plus intended untracked files into a new empty directory,
using `git ls-files --cached --others --exclude-standard -z` as the inventory after
output files have been removed from the index. Review that inventory before
copying; exclude `.git`, caches, `app/build`, and `app/bin`. Preserve executable
modes and include the new docs/workflow/scripts. Confirm that no generated Java
or compiled classes exist before running the two commands above. If a filename
was removed in the working tree, omit it from the snapshot as well.

An isolated `GRADLE_USER_HOME` makes downloaded tooling/dependencies explicit.
Reusing its dependency cache is acceptable; it must not supply project build
outputs. KF-001 used a fresh source directory and no Gradle build cache, with
cached downloads from its original-baseline run.

[The Linux workflow](../.github/workflows/build.yml) uses Ubuntu 24.04 and Temurin 17,
the wrapper, separate compile/package and test steps, then both packaged smoke scripts.
It has a 25-minute job limit and individual build/test limits. Available reports
upload with `if: always()` even after failures; earlier failed steps remain failed.
A build failure prevents tests; a test failure prevents the subsequent smoke step.
Missing reports are not passing tests. Action revisions are pinned to the official
[checkout](https://github.com/actions/checkout),
[setup-java](https://github.com/actions/setup-java), and
[upload-artifact](https://github.com/actions/upload-artifact) repositories.

Authoring this workflow is not evidence of a GitHub Actions run. See the dated
[KF-001 validation record](baseline/kf-001.md) for observed local results and limits.

KF-006 consumer protocol and storage extension: [design](design/kf-006-fetch-commit.md).

## KF-006 consumer flow

In opt-in durable mode, Join/Heartbeat return qualified `assignments` and a parallel
`committed_next_offsets` list of exactly the same length/order. Start at your assigned
committed position, call unary Fetch with session/incarnation/generation, qualified partition,
explicit start offset, positive count and key+payload byte budget, process returned bytes,
then Commit the next offset. Processing records 0–9 means Commit(10). Fetch alone never
commits. Continue Heartbeat: Fetch/Commit do not renew leases. Rejoin after restart and use
the new incarnation plus recovered assignment positions. Groups progress independently;
explicit earlier Fetch offsets replay retained records without rewinding progress.

Fetch is immediate and bounded: at end it returns an empty batch, unchanged continuation
and captured published end. Beyond end is OUT_OF_RANGE; a first record larger than the
byte budget is RESOURCE_EXHAUSTED. Key presence, original binary bytes and timestamps survive.
Equal/lower authorized commits report the effective position; the broker does not prove
application processing. A timeout/lost response has unknown outcome. Retry only with current
ownership. Rebalancing rejects work fenced during reads and defers assignment changes while
an applying progress write is unresolved. Join can temporarily return UNAVAILABLE for that
pinned group; surviving members can Heartbeat. Separate groups remain responsive.

`DurableBroker.reset(group, partition, target)` is internal administration only: group must
have no members or applying/uncertain progress. It deliberately permits backward movement
within the durable log. There is no reset network endpoint. Catalog V3 upgrades V1/V2 on
first effective progress mutation and requires per-group checksummed progress files.
Old binaries reject V3; do not downgrade a progressed store. See the [full contract](design/kf-006-fetch-commit.md)
for crash/upgrade states, filesystem assumptions, resource accounting and future segment pins.

`ConsumerOptions` (also an injectable durable Spring bean) bounds read workers/calls/bytes
and progress calls/bytes independently of Append and membership admission. Defaults: two
read workers, 16 reads, 64 MiB read charge, 64 progress calls, 1 MiB progress charge. Read
charge covers configured frame scratch, requested records/bytes, descriptors and response
conversion; it excludes caller-held values and transport buffers after handoff. Shutdown
waits for actual readers and the writer before releasing directory ownership; a bounded
shutdown timeout reports incomplete cleanup instead of interrupting or replacing I/O.

The actual durable smoke remains `python3 scripts/smoke_durable.py`. It now drives two
consumer groups through public Fetch/Commit, checks replay leaves progress unchanged,
SIGKILLs the first packaged broker after acknowledged progress, rejoins after restart,
checks distinct resumed positions (2 and 1), and commits to 4 and 3. Original exact Append
witness/recovery and normal shutdown checks remain. Process crashes are not power-loss proof.

KF-006 [local validation](baseline/kf-006.md): clean source build, 326 passing tests in
34 classes, original prototype smoke and extended durable consumer restart smoke. The
prepublication record distinguishes these local results from subsequent exact-commit CI.
