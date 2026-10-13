# Build and verify Kaflow

The [KF-002 contract](design/kf-002-broker-contracts.md) separates the injected
prototype runtime from the new in-process V1 core. Its generated V1 Append schema
is not a registered network service. The packaged smoke still checks the prototype;
V1 core guarantees require the focused tests, not that health check.

The current application is an in-memory prototype. A successful build or health
check does not establish durable acknowledgements, correct delivery/offset
semantics, Kafka compatibility, or performance. The active plan is [../todo.md](../todo.md);
old README/blog test-count and coverage claims are not baseline evidence.

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
across ten classes. Inventory is not execution evidence or coverage. Derive outcomes
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
the wrapper, separate compile/package and test steps, then the same smoke script.
It has a 25-minute job limit and individual build/test limits. Available reports
upload with `if: always()` even after failures; earlier failed steps remain failed.
A build failure prevents tests; a test failure prevents the subsequent smoke step.
Missing reports are not passing tests. Action revisions are pinned to the official
[checkout](https://github.com/actions/checkout),
[setup-java](https://github.com/actions/setup-java), and
[upload-artifact](https://github.com/actions/upload-artifact) repositories.

Authoring this workflow is not evidence of a GitHub Actions run. See the dated
[KF-001 validation record](baseline/kf-001.md) for observed local results and limits.
