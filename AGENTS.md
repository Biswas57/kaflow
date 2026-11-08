# Kaflow working agreement

## Product and source of truth

Kaflow is a lightweight single-node durable partitioned log. Java 17, REST diagnostics,
and the versioned custom gRPC API are supported. V1 uses fixed partitions, opaque payload
bytes, explicit Fetch and Commit, leased one-topic groups, durable Append acknowledgements,
bounded resources, and recovery. Linux local storage is the durability reference; retain
macOS development. Do not claim Kafka compatibility, replication, failover, transactions,
exactly-once processing, or unverified performance/durability.

Read this file and [todo.md](todo.md) before work. The architecture and operations guides
contain current contracts and procedures. Per-ticket historical evidence lives under
`docs/history/implementation/`; older prototype notes are historical, not current behavior.
KF-010 is complete under the user's explicit waiver of Docker validation, as recorded in
`todo.md`. No container execution or Docker acceptance is claimed. Do not start KF-011 without
a separate user request.

Generate Protobuf Java from `app/src/main/proto`; never edit generated code. Keep one
canonical lowercase `todo.md`. Inspect branch, user changes, source and test reports before
changing files. Executed source and checks outrank old README/blog claims.

## Ticket workflow

1. Select the first eligible ticket after dependencies complete; only one may be active.
2. Record branch/base, scope, acceptance criteria, and exact validation commands.
3. Implement the smallest reviewable change and preserve unrelated edits. Turn new findings
   into linked follow-up tickets instead of silently broadening scope.
4. Keep `todo.md` current with files, decisions, command results, blockers, and handoff.
5. Mark DONE only with evidence for every acceptance item. Distinguish pass, failure, not run,
   and environment block. Never weaken assertions, disable checks, or hide failures.
6. If a required capability is missing, finish independent work and mark BLOCKED with the
   exact pending command. Resume the same ticket when the capability is available.
7. Do not commit, push, publish, merge, or open a PR unless the user authorizes it.

## Engineering contracts

- Inject broker state; do not add mutable global singletons or dual-write prototype/V1 state.
- Explain client behavior and component ownership before implementation details.
- Keep coordinator work responsive: disk I/O and network writes run outside its loop; storage
  completions return to coordination; reads use bounded snapshots.
- Fetch never advances committed progress. Commit represents the next offset and validates
  current session, generation, and ownership when applied. Fence reassignment against commits
  until real storage completion resolves their barrier.
- A timed-out write may have an unknown outcome. Do not replace a possibly running writer or
  release its directory lock. Retain late-completion ownership until actual work unwinds.
- Preserve acknowledged retained data; fail closed on corruption within published history.
  Keep prototypes' legacy crypto outside the active V1 design.
- Long-poll requests must not occupy read workers; bound bytes and object counts.

## Validation and reporting

Use the wrapper and Java 17. Baseline server checks:

```sh
./gradlew clean :app:classes :app:testClasses :app:bootJar --no-daemon
./gradlew :app:test --rerun-tasks --no-daemon
```

For client changes also run `./gradlew :clients:installDist :clients:healthProbeJar --no-daemon`
and `./gradlew :clients:test --rerun-tasks --no-daemon`. Follow the ticket's packaged smoke
plan. Record environment, command, exit status, test count, errors/skips, timeout, and report
location. Process-kill tests are not physical power-loss evidence. A CI workflow or local
configuration is not an executed CI result.

Summarize changes, evidence, limitations, and the next exact action. Leave publication status
honest and do not perform it automatically.
