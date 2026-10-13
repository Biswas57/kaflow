# Kaflow agent working agreement

## Product goal

Evolve Kaflow into a lightweight single-node durable partitioned event-log broker.
Use Java 17, REST administration, and a versioned custom gRPC API. V1 has fixed
partition counts, opaque payload bytes, one-topic consumer groups, explicit Fetch
and Commit, durable append acknowledgements, bounded resource use, and recovery.
Linux local storage is the reference durability environment; retain macOS development.
Do not claim Kafka client compatibility, replication, failover, transactions,
exactly-once processing, or unverified performance/durability.

## Source of truth

- Read this file and `todo.md` before work. `todo.md` is the ticket board and Codex
  working paper: scope, decisions, evidence, blockers, and handoff live there.
- Inspect the current checkout and user changes. Source and executed checks outrank
  old README/blog claims. `docs/history/prototype-todo.md` is historical only.
- Generate Protobuf Java from `app/src/main/proto`; never hand-edit generated code.
- Keep one canonical lowercase `todo.md`; do not create a second case-only filename.
- KF-002 ownership, opaque-byte, admission, closure, and staged API contracts are in
  `docs/design/kf-002-broker-contracts.md`. V1 remains unregistered until durability.
- KF-003 storage framing, ownership, resource limits and non-durability boundaries are
  specified in `docs/design/kf-003-partition-log.md`.

## Ticket workflow

1. Select the first eligible ticket whose dependencies are complete. Only one ticket
   may be IN_PROGRESS. Expand its acceptance criteria before making changes.
2. Record the branch/base revision, intended changes, and validation commands.
3. Implement the smallest reviewable change in scope. Preserve unrelated edits.
   New findings become linked follow-up tickets rather than silent scope expansion.
4. Update the ticket after meaningful progress: files, decisions, command outcomes,
   blockers, and the exact next action. Keep enough context for another session.
5. DONE requires the ticket's acceptance evidence. Distinguish passed, failed,
   not run, and environment-blocked checks. Never weaken assertions, disable tests,
   or hide failures to complete a ticket.
6. If blocked, complete independent work within scope, mark BLOCKED, document the
   missing prerequisite, and do not start dependent tickets. Resume the same ticket
   when the prerequisite is available.
7. Do not push, publish, or open a PR unless the user requests it. Report local
   branch state honestly. Do not add recurring permission gates for routine edits.

## Engineering rules

- Explain client behaviour and component ownership before implementation details.
- Prefer incremental replacement of the prototype over an unreviewable rewrite.
- Inject broker state; do not introduce new global mutable singletons.
- Keep coordination responsive: no blocking disk I/O or network writes on its loop.
  Storage completions return to the coordinator; reads use bounded snapshots.
- Fetch never advances committed progress. Commits represent the next offset and
  require apply-time session/generation/ownership validation. Administrative resets
  are separate. Fence reassignment against unresolved applying commits.
- A timed-out write has unknown outcome. Do not replace a possibly running writer
  or release its directory lock. Define late-completion handling before storage work.
- Before durability work, document crash states, publication boundaries, bounded
  checkpoint costs, corruption policy, and filesystem assumptions.
- Introduce immediate bounded Fetch before asynchronous long polling. Waiting
  requests must not occupy read workers. Bound aggregate bytes and object counts.
- Preserve acknowledged retained data; never silently recover by discarding corrupt
  acknowledged records. Keep prototype custom crypto outside the active V1 design.

## Validation and reporting

Use the Gradle wrapper and Java 17. Baseline commands:

```sh
./gradlew clean :app:classes :app:testClasses :app:bootJar --no-daemon
./gradlew :app:test --rerun-tasks --no-daemon
```

Record environment and startup/shutdown evidence. Behavioural fixes require focused
tests of observable guarantees; concurrency/recovery tests use clocks and failpoints
where appropriate. Process-kill tests are not physical power-loss tests.
Never present test counts as coverage, configuration as runtime proof, or CI authored
locally as CI executed. Summarize what changed, evidence, limitations, and next ticket.
