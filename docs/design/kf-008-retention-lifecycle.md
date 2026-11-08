# KF-008: retention, storage quotas and lifecycle

Design recorded before implementation, 2026-10-16. Status and executed evidence live in
[the working paper](../../todo.md) and [validation record](../baseline/kf-008.md).

## Client behaviour and configuration

Retention is disabled by default (`retentionBytes=0`). An explicit positive per-partition target removes
only an oldest contiguous prefix of sealed segments when retained log plus index lengths
exceed the target. The active segment always survives, even if it alone exceeds the target.
Offsets and segment bases never change or get reused. No compaction, time policy, topic
deletion or payload rewrite. Retention is independent of commits; lagging groups can lose
history. It neither advances nor resets their durable progress.

Immutable LifecycleOptions bounds the target, total managed storage and per-partition
segment storage. Defaults: retention disabled, 8 GiB total, 1 GiB per partition; hard ceiling
16 TiB. Positive retention targets may not exceed the partition ceiling. Constructors reject
invalid arithmetic/configuration; opening validates room for configured maximum frames,
rotation headers and mandatory maintenance headroom. StorageOptions still bounds segment
size/count, including expired segments awaiting physical deletion.

The readable range is [retainedStart, publishedEnd). Fetch below start or above end returns
OUT_OF_RANGE; at end the existing immediate-empty/optional-wait behaviour remains. No implicit
cursor movement. Join/Heartbeat expose actual committed positions plus parallel retained-start
and published-end vectors, in assignment order, bounded by the fixed partition count.
Effective Commit advances and every inactive reset must be within current bounds at application.
Equal/lower ordinary commits remain authorized idempotent no-ops, even if retention overtook
the old progress. Ownership and the existing commit/reset/rebalance barriers still apply.

## Authority, upgrade and recovery

Checkpoint V2 adds an int64 retained start before topic bytes (92+topicBytes, max 347), with
the same identity/CRC and end/base/bytes/unkeyed fields as V1. Start must be nonnegative and
no greater than the active base; a nonempty retained prefix starts exactly at a segment base.
Each update preserves end/unkeyed counts and monotonically advances start. CRCs cannot detect
malicious rollback of otherwise valid old metadata; external mutation remains unsupported.

Catalog V4 uses the V2/V3 layout, allows zero groups, and flag bit 0 records whether progress
files are required. It requires V2 checkpoints for every partition. Retention-enabled open
first validates all existing authority/data, writes V2 checkpoints with start zero, then
forces/atomically installs/syncs V4. Before V4, V2 checkpoints are accepted only with start
zero, so an interrupted upgrade cannot authorize deletion. New topics in V4 use V2 directly.
Progress upgrade preserves V4 and its flag. No automatic upgrade with retention disabled,
but disabled policy can reopen V4 and must finish already-authorized deletion. Old binaries
reject V2 checkpoints/V4 catalogs; downgrade is unsupported once upgrade starts. Missing
required checkpoints never imply start zero. No standalone KF-003 migration.

The single storage writer serializes append, rotation, checkpoint, progress and retention.
It prepares a bounded prefix, installs and synchronizes a checkpoint authorizing expiration,
then advances the shared pin registry's floor and issues a new immutable snapshot.
The shared floor is the logical publication point: coordinator validation and membership
range diagnostics consult it even while a deletion batch is still running. Only then
may it delete expired unpinned segments. Directory force confirms deletion before quota credit.
Indexes are derived but deletion/accounting follows the same segment. No full directory scan
or history rewrite on ordinary append/retirement. One checkpoint is bounded independently of
history. Recovery validates every retained published byte and all authority before repairs;
expired surviving files never resurrect history. Recognizable unsupported versions, unknown
names and nonregular files fail closed. Missing/corrupt retained records fail closed.

| Interrupted point | Recovery / ownership rule |
| --- | --- |
| Upgrade checkpoint write/force/rename | Old catalog permits only zero-start upgrade files; validate, then resume upgrade |
| V4 catalog installation/sync | Old or new catalog is safe; neither permits nonzero start without V4 |
| Retention temporary write/force | Old authoritative checkpoint; no deletion has begun |
| Retention rename before directory force | Old/new boundary may survive; data is still present for either |
| Authority synchronized, before logical publication | New start is authoritative; recovery expires prefix, end unchanged |
| Logical publication before unlink | Old files remain charged and invisible to new readers |
| Individual log/index unlink | Authority permits either file to be absent; validate retained range before continuing |
| Deletion directory sync | Repeat idempotent deletion and sync; never credit uncertain removal in a live failed owner |
| Interrupted restart cleanup/index rebuilding | Authority unchanged; repeat bounded validation/cleanup, no readiness until complete |
| Missing/corrupt retained range or authority | Fail closed, preserve files, no inference of a smaller range |

## Read pins and bounded maintenance

Each partition has one shared synchronized pin registry keyed by immutable segment base.
Snapshot admission checks the current floor and acquires all potentially accessed segment
pins atomically relative to floor advancement. No I/O under that monitor. Pins precede worker
queueing/channel open and last until actual read/channel cleanup unwinds, including cancellation
or watchdog timeout. Previously admitted snapshots remain readable under pins after expiration;
completion still checks current membership, cancellation, lifecycle and group barriers, but
does not retroactively apply a new retained floor. Parked requests have no snapshot or pins.

Retirement never unlinks pinned files. Retired descriptors plus active descriptors remain
within maxSegments. A stuck reader can therefore exhaust quota/count and reject append;
it cannot permit unbounded file/descriptor growth. Pin release coalesces a maintenance signal,
never creates a task per pin. Maintenance processes one partition and at most four deletions
per writer turn, alternates with admitted mutations, and visits dirty partitions fairly.
Coordinator work is bounded metadata only. A completion slot remains reserved independently
of ingress; controls/cancellation/diagnostics retain their existing capacity.

## Honest storage accounting

Count exact segment log and index lengths, including expired files until deletion and
directory sync succeed. Reserve worst-case append growth before any mutation: frame, optional
index entry, and rotation log/index headers. Each referenced partition has its own byte ceiling.
The total ceiling also permanently charges a conservative maintenance envelope: maximum
catalog and replacement, all configured publication/progress authorities and replacement
overlap (including one unpublished topic/group), root marker, plus one maximum derived-index
rebuild scratch file. Oversized scratch, more than one index rebuild scratch file, and nonempty partition lock
markers fail validation. Initial orphan segment lengths count globally during recovery;
they are not misclassified as one partition. This envelope
covers initial progress/retention upgrades as well as ordinary metadata replacement. It is
unavailable to appends, so filling the data budget cannot itself prevent safe expiration.
Recovery checks actual segment lengths, including initial orphan files, before repair and
reconstructs them after validated cleanup and reinstates the same
conservative metadata charge. Checked arithmetic; pending growth stays reserved through the
real writer outcome, never client timeout. Failed uncertain mutation conservatively consumes
its reservation until close/recovery. No quota credit for scheduling or logical expiry.

Quota rejection is RESOURCE_EXHAUSTED and does not degrade. Retry may succeed only after
confirmed physical reclamation. Actual I/O/ENOSPC, corruption or uncertain persistence latches
degradation; free space never clears it. Quotas measure managed file lengths/conservative
reserves, excluding allocation blocks, directories, unrelated files, OS caches and total heap.
Actual filesystem ENOSPC can still occur. Bounded diagnostics report accounted/reserved bytes,
retired files, pins and lifecycle; a full metrics/release surface belongs to KF-009.

## Shutdown

READY/DEGRADED -> CLOSING -> CLOSED. One caller deadline uses elapsed System.nanoTime across
all waits. Close stops admission/readiness, invalidates membership, drains parked/queued work,
and stops new maintenance. Active append/commit/deletion resolves on its actual writer;
unknown outcomes retain barriers/reservations. Actual queued/active reads unwind before their
pins release. Response delivery must unwind before storage close/unlock; no CLOSED claim while
callbacks can still access the broker. Executor termination is included in the total budget.

A deadline returns false/incomplete; no interrupt, replacement writer, forced pin release or
counter reset. The live owner retains locks until all relevant work finishes. Late completions
continue cleanup automatically, and repeated shutdown can await that cleanup. Close failures
preserve their primary cause and suppressed cleanup failures. The composition root transfers listener stop/termination observation and transport-executor
ownership to the broker. Listener shutdown stops new RPCs while dispatch remains available
for required completion/cancellation callbacks; after RPC termination and admission drain,
shut down that executor, await real termination, then close storage. Durable Spring lifecycle
uses one ten-second budget for this sequence and repeated destruction does not restart it.
The starter's factory discovery remains intact; its lifecycle instance is replaced after
initialization. A fallback bean destructor handles startup failure with no lifecycle owner.
Packaged checks separately verify both HTTP and gRPC listeners close.
Java cannot safely cancel arbitrary blocked I/O; an external supervisor may terminate a stuck
process. Linux local storage remains reference; force, atomic same-directory rename, directory
force and honored device flushes are assumptions. Process-kill tests are not power-loss proof.

## Validation plan

Preserve inherited assertions and add deterministic boundary/byte/offset, every crash point,
corrupt/missing authority, interrupted recovery, pin-before-open/queued/cancel, independent
progress/reset/Join, quota/rotation/temp/retired accounting, overload and shutdown tests. Use
barriers and injected I/O/clocks, bounded children and separate-process lock probes. Extend
packaged validation with explicit small retention and restart, exact binary reads/public
OUT_OF_RANGE and shutdown under load. Run clean source-only compilation/package, separate
full tests, original smoke, durable two-group/parked smoke and focused retention smoke. Record
actual counts/failures/timeouts and manifest provenance; no CI execution claim without a run.
