# Kaflow ticket board and working paper

Last updated: 2026-11-09. Current ticket: **KF-010**. The user authorized commit, push, and
PR creation on 2026-11-09; merging is not authorized. KF-010 is DONE under the user's explicit
waiver of Docker validation; the container smoke was not run and is not reported as passed.

| Ticket | Scope | Status | Evidence |
| --- | --- | --- | --- |
| KF-001 | Reproducible source build and baseline | DONE | [record](docs/history/implementation/kf-001.md) |
| KF-002 | Injected ownership and V1 Append contracts | DONE | [record](docs/history/implementation/kf-002.md) |
| KF-003 | Partition log framing and bounded reads | DONE | [record](docs/history/implementation/kf-003.md) |
| KF-004 | Durable publication and crash recovery | DONE | [record](docs/history/implementation/kf-004.md) |
| KF-005 | Durable groups and leased membership | DONE | [record](docs/history/implementation/kf-005.md) |
| KF-006 | Immediate Fetch and durable Commit | DONE | [record](docs/history/implementation/kf-006.md) |
| KF-007 | Bounded asynchronous Fetch waiting | DONE | [record](docs/history/implementation/kf-007.md) |
| KF-008 | Retention, quota, and bounded lifecycle | DONE | [record](docs/history/implementation/kf-008.md) |
| KF-009 | Operable release, containers, metrics, benchmarks | DONE | [record](docs/history/implementation/kf-009.md) |
| KF-010 | Repository cleanup and current architecture | DONE | Local checks passed; user waived Docker validation. Container execution remains unverified. See [record](docs/history/implementation/kf-010.md) |

READY describes scoped backlog; it does not allow bypassing an incomplete dependency.
Only one ticket may be active. Keep historical counts tied to the ticket/source that ran them.

## Accepted product direction

Kaflow is a lightweight single-node durable partitioned event log. Use Java 17, REST
diagnostics, and the custom versioned gRPC API. V1 has fixed partitions, opaque bytes,
durable Append acknowledgements, leased one-topic groups, explicit Fetch and Commit, bounded
resources, and recovery. Linux local storage is the reference environment; macOS remains a
development platform. Kaflow is not Kafka-compatible and does not provide replication,
failover, transactions, deduplication, exactly-once processing, or unverified performance
claims. Current component and data-flow contracts are in [architecture](docs/architecture.md);
operational procedures are in [operations](docs/operations.md).

## KF-010 — Completion record

### Starting state and scope

- Branch: `codex/kf-010-repository-cleanup`.
- Base: `a699c22e09b0400dd476eac6e18b3f0cc5001e79`, merged KF-009 main.
- Final local check environment: macOS 26.6.2 arm64, Temurin 17.0.20.1+1, Gradle 8.5.
  This turn does not claim Linux storage/durability execution.
- Native filesystem evidence is from APFS. It does not verify Linux force/atomic-replacement
  behavior or physical power-loss recovery. Docker volume persistence, ownership, and shutdown
  remain unverified; the user explicitly waived container validation on 2026-11-09.
- The checkout was clean before KF-010. Initial Git/source inventory and prior state are
  captured under ignored `build/kf010-evidence/entry/`.
- Preserve the staged ticket scope: reproduce and correct the inherited Join cleanup test's
  completion-versus-delivery assumption; remove only verified redundancy; consolidate to five
  current guides and per-ticket evidence;
  preserve public behavior, storage/wire/config formats, historical authored material, and
  unrelated state. The original plan excluded publication; the user later authorized committing,
  pushing, and opening a PR. No merge and no KF-011.

### Implementation and final local evidence

**Join response cleanup test.** A deterministic barrier reproduced the inherited test race:
the Join future was complete while its response callback still held pending-Join/control
admission and byte reservations. A completed future therefore did not mean the admission slot
was reusable. While the callback was held, a competing Join was rejected and the cleanup
observer timed out. The test releases the callback, waits for the actual counters to reach
zero, then advances the fake clock and retries Join at generation 3.
`awaitControlCleanup(Duration)` is a package-private bounded test observer. The regression
holds delivery, proves the reservation remains and cleanup wait times out, releases the
callback, waits for zero accounting, then advances the fake clock and admits the next Join at
generation 3. The existing policy and capacity assertions remain unchanged. The isolated
focused rerun passed in 0.145s; its JUnit XML is
`build/kf010-evidence/final/focused-join-regression.xml`. The original reproduction and earlier
fixed reports remain under `build/kf010-evidence/baseline-repair/` and `commands/`.

**Gradle and resource audit.** Full `:app` and `:clients` test compile/runtime graphs were
resolved before and after cleanup with selected component IDs, variants, attributes,
capabilities, requested/selected edges, artifact names, and SHA-256 hashes. App counts changed
87→86 compile components and 98→97 runtime components; the only component/artifact removed is
unused `system-lambda:1.0.0`. Only the direct root edges for `system-lambda` and Mockito were
removed; Mockito 4.5.1 remains selected through Spring's starter. Every other shared component,
version, variant, capability, edge, and artifact hash is identical. Client graphs remain 23
compile and 31 runtime components with no edge/artifact changes. Resource directories, input
names, and hashes were identical: `application.properties`, `broker.proto`, and `tributary.proto`;
duplicates were empty and the effective resource strategy was INCLUDE before and after. The
source directories equal Gradle defaults. The resolved graph comparison is retained at
`build/kf010-evidence/final/dependency-graph-comparison.json`. The pre-cleanup JAR
was captured with 429 entries and SHA-256
`764f9428f559e7a9273789a1f49fc8594ea26bc74342b14378267e1c4eb30c0c`. The final JAR also has
429 entries and SHA-256 `038856834b3cd211eff87dd8d6660f7295803cd3ff07676ff0f444ea06b5a1f9`:
all nested libraries, application resources, and outer metadata hashes match. Only
`TributaryController.class` differs; `javap -c -p` output is identical, and after stripping
its shifted debug `LineNumberTable`, all other `javap -c -l -p` output is identical. Corrected
audit tooling failures are retained; the resolved before/after audit succeeded.

**Code cleanup.** Removed six statically verified unused imports, direct test declarations
for `system-lambda` and Mockito, source-set declarations matching defaults, and the
`processResources` INCLUDE override after recording collision-free inputs. Removed six
tracked Finder `.DS_Store` metadata files. Common JAR discovery, listener checks, paired-port
selection, and safe packaged extraction now belong to `release_support.py`. Executable smoke
scripts import the support module, never one another. Existing CLI flags, result fields,
timeouts, output paths, response checks, and distinct prototype-health/durable-readiness
checks are preserved.

### Document and artifact inventory

| Disposition | Items | Evidence / verification |
| --- | --- | --- |
| Keep | Runtime prototype, `InMemoryBroker` contracts, old readers, response executors, lifecycle processor, cancellation guards, reader pins/accounting, failpoints, independent encodings | Active code paths, V1 contracts, and focused test inventory; no deletion planned |
| Consolidate | Root README, AGENTS, todo, architecture, operations | These are the five current guides; exact guide links are at top of this file |
| Consolidate | Eight `docs/design/kf-*` files, nine `docs/baseline/kf-*` files, development/configuration/examples/benchmarks guidance | Current contracts moved to architecture/operations; unique source identities, commands, failures, environments, CI URLs, and benchmark method/results moved to `docs/history/implementation/kf-001.md` … `kf-010.md`. `docs/examples.md` was introduced in KF-009 commit `84dfe18`; the operations section preserves its commands and body, apart from the new section title and trailing separator. |
| Remove | Six `.DS_Store` files | Finder metadata only; all other user-authored historical files remain at their original paths |
| Remove | Six unused Java imports | Tokenized-source scan removed only imports whose symbols had no executable reference |
| Remove | `system-lambda` and direct Mockito declarations | Full graph shows Mockito 4.5.1 remains through Spring starter; only unused system-lambda component leaves the graph |
| Remove | Explicit `sourceSets` and `processResources` override | Inputs match Gradle defaults; zero duplicate paths; final packaged resources and nested libraries match the captured pre-cleanup JAR |
| Historical | Pre-existing `Blogging&Design/**`, requirements, prototype README/todo, imported baseline paper, editor-build archive, `messageConfigs`, commented legacy code and examples | Preserve contents and paths. All 41 protected authored/configuration files across the preserved-artifact groups match the entry hash/mode inventory; `prototype-readme.md` is byte-for-byte unchanged and retains its historical root-relative links. `docs/examples.md` was added by KF-009 and is eligible for consolidation. |
| Defer | Retirement of prototype/scaffolding, old commented classes, auth/TLS, public reset, APIs, broad broker refactor | Outside KF-010 and/or changes established contracts |

No public API, Protobuf field, configuration key/default, storage format, upgrade path,
test guarantee, or existing command is intentionally changed. Historical benchmark values
remain attached only to their original manifest in the KF-009 archive.

### Acceptance and evidence status

- [x] Record branch/base/source inventory and preserve entry snapshot.
- [x] Make the inherited Join cleanup test deterministic with a callback barrier; verify the
  slot stays reserved until callback cleanup, wait for zero accounting before advancing the
  fake clock/retrying Join, and preserve production admission behavior and assertions.
- [x] Compare full test dependency graphs and source resource inputs; verify selected versions,
  capabilities, and required transitive dependencies.
- [x] Consolidate smoke support without imports between executable scripts.
- [x] Consolidate current docs into five guides and add three architecture diagrams plus
  per-ticket implementation evidence records.
- [x] Run final clean Java 17 server/client source build/package and separate full server/client
  test tasks; recount XML results (472 + 11, zero failures/errors/skips).
- [x] Run prototype, durable, retention, and release packaged smokes plus benchmark
  correctness; record results and report paths below.
- [x] Compare final packaged resources, verify smoke CLI/help and result fields through
  successful executions, current-guide/archive links, removed-symbol references, balanced
  Mermaid blocks, and the final source manifest. The preserved prototype README is excluded
  from current-link validation because its historical root-relative links are unchanged.
- [x] Compare protected historic hashes/modes, review the full diff for secrets/generated
  artifacts and unrelated edits, and record the container-engine preflight.
- [x] **User-waived on 2026-11-09:** Do not run container validation. The exact candidate
  Docker context plus smoke harness is enumerated by
  `build/kf010-evidence/final/container-input-manifest.json` (120 files, SHA-256
  `98b19e208bd1ad6cf428f9569d565b3cdc7b625cf765e298b3984e7c6405f6dc`). The earlier
  preflight exited 2 at `app/build/reports/container-smoke/run-jlsfsnkt/result.json` before
  image build because Docker CLI, Compose plugin, and a reachable engine are absent. It is not
  container execution evidence. This user waiver closes the ticket without claiming Docker
  acceptance.

Final local environment: macOS 26.6.2 arm64/APFS, Eclipse Temurin 17.0.20.1+1, Gradle 8.5.
The clean Java 17 build/package succeeded with 21 Gradle tasks:
`clean :app:classes :app:testClasses :app:bootJar :clients:installDist :clients:healthProbeJar`.
The separate `:app:test :clients:test --rerun-tasks --no-daemon` run succeeded with **472
server tests and 11 client tests** (483 total; zero failures, errors, or skips; 18 Gradle
tasks). Current XML recounts sum to 60.772 seconds for app and 0.033 seconds for clients;
counts are test invocations, not coverage. XML reports are under
`app/build/test-results/test/` and `clients/build/test-results/test/`.

Final-source package checks passed: the prototype smoke reported readiness UP, REST create/get,
SIGTERM exit 143, no timeout, and both listeners closed. The durable smoke passed SIGKILL/restart,
verified four acknowledged records across two independent groups, and delivered a parked Fetch
with one waiter and zero readers before append. The retention smoke passed two phases with
retained-start publication, 16 parked waiters and zero pins at shutdown. The release smoke passed
closed-copy restore and actual configuration-precedence gauge checks. Benchmark correctness
passed six positive scenario trials and both expected-negative checks (setup failure and receipt
cap) were classified correctly, with no harness failures. Results are in
`app/build/reports/kf010-final/{bootjar,durable,retention,release}/result.json` and
`app/build/reports/kf010-final/benchmark-correctness/result.json`. Process-restart checks do not
establish physical power-loss behavior.

Final review passed `git diff --check`, Python parsing and all six smoke/benchmark `--help`
checks, and Markdown link/anchor validation over the five current guides, history index, and
ten ticket records (16 files, 31 local targets, zero broken links). Executable smoke scripts
import no other executable smoke script. All 41 protected historic/authored/configuration
files matched the entry hash/mode inventory; the prototype README is byte-for-byte unchanged.
The six Finder metadata files were removed. The changed-file credential scan found no matches.
No generated class outputs or application JARs are tracked; the existing Gradle wrapper JAR is
checked-in tooling. The architecture has three balanced Mermaid blocks;
no renderer is installed. The final 260-file source manifest and exact 120-file Docker
context/harness manifest are under ignored `build/kf010-evidence/final/`; the source manifest
was refreshed after the documentation edits, and the benchmark-captured manifest differs only
in documentation files.

Raw command logs, exit/timing JSON, Gradle graph snapshots, pre-cleanup package manifest,
source inventory, fixed/reproduced Join reports, and prior history are under ignored
`build/kf010-evidence/`. Final evidence must state whether checks ran locally, in CI, or were
blocked; an older CI result is not evidence for modified KF-010 inputs.

### Verified inherited CI boundary

The inherited plan cites current-main [Actions run 42462162038](https://github.com/Biswas57/kaflow/actions/runs/42462162038)
as passing native and container checks at the merged KF-009 source. Internet access in this
KF-010 turn did not independently retrieve the run. Treat that result as user-reported unless
the retained prior evidence provides an exact inspected response. It predates KF-010 cleanup
and is not evidence for KF-010. The user waived container validation; no container execution
is claimed. Prior KF-009 exact-tree CI is recorded in
[its archive](docs/history/implementation/kf-009.md).

### Blocker and handoff

KF-010 is **DONE under the user's explicit waiver** of Docker validation on 2026-11-09. The
container command was not run after the waiver; an earlier environment preflight exited 2
before image construction. No Docker execution, Linux container behavior, or volume acceptance
is claimed. The candidate context manifest remains available if the user later requests that
validation. Do not begin KF-011 without a separate request. No commit, push, PR, or merge.
