# Kaflow V1 tickets and Codex working paper

## Board

Status: READY, IN_PROGRESS, BLOCKED, DONE. One active ticket maximum.
Last updated: 2026-10-13. Current ticket: **KF-001 (BLOCKED)**.

| Ticket | Scope | Status | Dependencies |
| --- | --- | --- | --- |
| KF-001 | Reproducible source build and honest baseline | BLOCKED | None |
| KF-002 | Injected broker ownership and V1 record/API contracts | READY | KF-001 |
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

**Status:** BLOCKED (Gradle distribution download unavailable in this environment).
**Branch:** `codex/ticketed-v1-baseline`.
**Base:** `a3b9026a8c39fd7b8edd47992467d576eb72ee62`; origin/main checked on 2026-10-13.

### Scope

Establish source-only generation/build, record existing test outcomes, add local
instructions and Linux CI reporting. Preserve assertions and production behaviour.
Do not redesign APIs, repair broker behaviour, upgrade the framework, or reactivate
the six commented historical test suites in this ticket.

### Acceptance

- [x] Record base revision, environment, and original build attempt separately.
- [x] Stop tracking generated Java and compiled output; preserve source `.proto`.
- [x] Document Java 17 macOS/Linux commands and add Linux CI with report retention.
- [ ] Clean source checkout compiles and packages after regeneration.
- [ ] Record actual outcomes of all seven active tests (failures may remain explicit).
- [ ] Record packaged application startup and shutdown behaviour.
- [x] Review diff and record static checks; distinguish them from runtime validation.

### Work log — 2026-10-13

- Verified clean checkout and origin/main at the base above; created working branch.
- Preserved old Todo.md verbatim as docs/history/prototype-todo.md; replaced its role
  with this lowercase board. Added AGENTS.md for future sessions.
- Environment: Linux x86_64; OpenJDK 17.0.20, build 17.0.20+8-1-24.04-Ubuntu.
- Used detached disposable worktree `../kaflow-baseline` for the original attempt:
  `./gradlew :app:clean :app:build --no-daemon` exited 1 before Gradle execution.
  Download of `https://services.gradle.org/distributions/gradle-8.5-bin.zip` failed
  with `java.net.SocketException: Network is unreachable`.
- This is an environment/download blocker, not evidence of compilation/test failure.
  Protobuf generation, compilation, packaging, test execution, and startup remain
  unverified. No tests ran. Source inspection finds 7 active @Test methods in 3 classes.
- Removed app/bin and app/build outputs from the Git index only; local generated files
  remain available. Updated ignore rules. No application source or test assertions changed.
- Static checks passed: staged/unstaged `git diff --check`; workflow YAML parsing;
  historical TODO byte-for-byte comparison; no tracked app/bin or app/build entries;
  output paths match ignore rules; no app/src diff. CI is authored but has not run.

### Resume instructions

In an environment with Java 17 and access to the Gradle distribution, plugin portal,
and Maven dependencies, run the commands in docs/development.md from a source-only
checkout (ensure ignored app/bin is not on the classpath). Record generation/build
and test results here, then execute the documented startup/shutdown smoke procedure.
Fix only observed build blockers within scope; create follow-up tickets for behavioural
failures. Do not mark DONE until compilation/packaging and evidence requirements hold.

## Remaining ticket acceptance outlines

Expand the next eligible ticket into scope, decisions, steps, evidence, and handoff.

- **KF-002:** injected isolated broker state; immutable byte records and qualified
  identities; bounded admission; deterministic routing; truthful results; invalid
  partitions fail; concurrent offsets/order tested. Replace obsolete active components
  incrementally. Include test-isolation repairs explicitly.
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
