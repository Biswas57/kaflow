# KF-004: durable publication and recovery

Design recorded before production changes, 2026-10-14. Implementation/evidence status
is maintained in [the working paper](../../todo.md). This extends KF-003 without
changing its standalone non-durable API or disk frames. No volatile broker adapter.

## Ownership and the success contract

Opt-in durable mode owns a separate explicitly configured directory and namespace.
Only successful recovery permits registration of unary V1 Append. Default prototype
REST/gRPC behaviour remains separate; its topic administration cannot create V1 topics.
An internal, bounded createTopic operation is provided for composition/tests/fixtures.

A dedicated writer thread owns the root OS lock, all partition locks and all storage
I/O. A coordinator owns topic/routing state, pending operations, lifecycle and published
next offsets. It performs no file operations or response callbacks. Mutations are
globally serialized: at most one storage operation is dispatched. Queued requests are
routed only at dispatch; offsets are assigned only by PartitionLog. Successful unkeyed
durable publications advance round-robin; explicit, keyed and rejected requests do not.
Canceled/lost-response operations that publish still count as successful publications.
There is no overlapping-producer ordering protocol or cross-partition order promise.

Writer completions carry operation identity and lifecycle epoch back to the coordinator.
The coordinator first advances published state, then hands the response to a bounded
response executor. A success means the exact record, its identity metadata and durable
publication checkpoint have been synchronized, and the record is already readable.
No success comes from KF-002 receipts, a completed write alone, close or an arbitrary future.
Failures/timeouts can have unknown outcomes; surviving unacknowledged writes and duplicate
records after retries are permitted. No idempotent producer or exactly-once claim.

## Filesystem contract

Linux local filesystems are the reference environment; macOS is supported for development
and process-restart tests. Require a persistent local filesystem implementing FileChannel
force(true), atomic same-directory replacement and directory force. Reject known remote,
memory-only and unsupported filesystem/OS types. Operation failures never downgrade to
volatile success. Device/controller caches must honor OS flush requests; filesystems must
preserve forced file data and forced rename/directory ordering. The portable Java API does not promise a particular syscall; the OpenJDK reference
implementation uses fsync for metadata force on Linux and F_FULLFSYNC on macOS. This
is not hardware validation or a physical power-loss guarantee. Process-kill tests prove only the
observed process-crash cases, not every device, filesystem or power failure.

Reference semantics: [Java 17 FileChannel.force](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)),
[OpenJDK Unix force implementation](https://github.com/openjdk/jdk17u/blob/master/src/java.base/unix/native/libnio/ch/FileDispatcherImpl.c),
and [Linux fsync](https://man7.org/linux/man-pages/man2/fsync.2.html). Linux requires a
separate directory sync for directory entries. Force record data on its writing channel;
recovery truncation is forced on the channel performing that truncation.

The configured root must have an existing parent. If newly created, synchronize that
parent. Ordinary root symlink aliases canonicalize; child symlinks/nonregular entries,
concurrent external file mutation and malicious hard links are unsupported/rejected.
Keep lock inodes permanently. Never release ownership while a writer may still mutate.

## Layout and exact durable metadata format

Root: `.kaflow.lock`, `catalog`, optional `catalog.tmp`, and `t-00000000` etc.
The root lock inode also contains the exact 17 ASCII bytes `KAFLOW-DURABLE-1\n` after
initialization begins. Read/write/force this marker only through the already owning lock
channel, never another channel on that inode. Partition lock files remain empty. Reject
nonempty unrecognized/partial markers. A successful store cannot be reinitialized as new
merely because its catalog disappeared, even when it has no topics.
Topic directory: `p-00000000.pub` checkpoint (optional `.tmp`) and `p-00000000/`
KF-003 partition directory. Topic slots are sequential catalog integers, never raw
topic strings. No path is derived from unchecked application text. All topic identities
use strict UTF-8; malformed surrogates reject before mutation.

Both metadata formats use big-endian integers, version uint16=1, flags uint16=0,
total byte length int32, CRC32C over all preceding bytes. CRC detects damage, not attacks.

Catalog: magic `KFCA` (4), version (2), flags (2), total length (4), store UUID (two
int64, 16), generation (int64, 8), topic count (int32, 4); then ordered entries of
slot (int32), fixed partition count (int32), UTF-8 byte length (int32), exact topic
bytes; trailing CRC (4). Total = 44 + sum(12 + topicBytes), hard maximum 64 KiB.
Generation equals topic count; slots are 0..count-1, identities unique. No deletion
or partition expansion. Catalog is rewritten only at topic creation, never per append.

Checkpoint: magic `KFCP` (4), version (2), flags (2), total length (4), store UUID
(16), topic slot (4), partition (4), topic byte length (4), generation (8), durable
next offset (8), final segment base (8), final segment byte end (8), cumulative unkeyed
publications in this partition (8), exact topic bytes, trailing CRC (4). Total = 84 +
topicBytes, at most 339 bytes. Generation equals next offset (one publication per record).
For an empty partition: generation/end/base/unkeyed=0 and byte end=segment header length.
Validate UUID, topic slot/identity, partition, generation/end, byte positions and counts.
Per-topic round-robin recovers from the sum modulo partition count of per-partition
unkeyed counts; sum using modular arithmetic. Unkeyed count cannot exceed end offset.

Reject lengths before variable allocation and unsupported authoritative versions before
repair. Sparse indexes remain KF-003 derived data and may be rebuilt. A missing or corrupt
checkpoint is never replaced by an inferred boundary or temporary file.

## Persistence ordering and bounded costs

Topic creation: prepare a new slot and all empty KF-003 logs; force each segment header;
sync each partition directory; write/force/replace its zero checkpoint and sync the topic
directory. Sync the topic directory and root directory before replacing the catalog.
Write catalog.tmp using CREATE_NEW, complete bounded writes, force it, atomically replace
catalog, sync root, then publish topic metadata and acknowledge internal creation.
Force the root marker and sync its directory before writing the initial empty catalog;
force/sync that catalog before accepting any topic.

Append: validate limits/next offset; write the complete KF-003 frame and required index
entry; force the active log including file metadata; sync partition directory (also covers
new segment names); write checkpoint.tmp; force temporary checkpoint; atomically replace
checkpoint; sync topic directory; advance writer durable boundary; return completion;
coordinator advances published boundary/routing; response executor sends success.
Index forcing is unnecessary: recovery validates authoritative logs and rebuilds indexes.
Prior segments were forced on earlier publications before rotation. New segment header
and frame are forced together before any checkpoint can mention them.

Written W, durable D and published P are exclusive next offsets: P <= D <= W in a live
instance. Recovery uses the authoritative checkpoint as D, validates every byte below it,
and initializes P=D. A rename before its directory force may survive a crash; old or new
checkpoint is safe because new checkpoint installation always follows forced data/names.
No client success precedes directory force. A failed force is an uncertain outcome.

Cost per append is one bounded frame/index write, one active-log force, two directory
forces, and one <=339-byte forced atomic checkpoint replacement. It never rewrites the
catalog or scans history per append. One checkpoint per append, no timer-driven backlog.
Recovery uses at most three bounded log passes: published-prefix validation, KF-003 open
validation, and optional derived index rebuilding.
No measured throughput or latency promise. No retention in this ticket.

## Recovery and interrupted repair

Acquire root and all referenced partition ownership before validation/repair. Read and
validate bounded catalog/checkpoints and all published log prefixes before modifying any
authoritative files. Missing/truncated/corrupt/wrong-identity published bytes fail startup.
Validate recognizable authoritative versions in tails before deleting them; unsupported
versions fail closed. Never modify a file understood to be from a newer format.

A genuinely empty root (apart from an empty lock inode) can initialize. A marked or
nonempty root missing its catalog, including interrupted first bootstrap, fails closed. Standalone KF-003 directories
have no publication evidence and are rejected by durable mode; no automatic migration.

Trustworthy checkpoints permit truncating only bytes beyond their final segment byte end
and deleting only later segments at/above the durable next offset. Force truncation before
directory sync. Delete matching derived indexes as needed; rebuild from validated retained
frames. Checkpoints remain unchanged during repair, so interruptions can repeat safely.
Complete unpublished frames follow the same discard policy as incomplete tails. Readiness
is never reported until repairs and their directory synchronization complete.

The catalog proves that a single next-slot topic directory left by interrupted creation
is unpublished only when it is consistent with interrupted creation: initial headers
and zero publication checkpoints, possibly partial. Any records, noninitial segment or
nonzero publication evidence contradict that catalog and fail closed. Bounded known-layout
cleanup may remove only consistent scratch. Unknown slots/names reject.
Leftover checkpoint/catalog temporary files are never promoted; after authoritative
metadata and versions validate, remove scratch files and sync their parent directories.
Partial bootstrap without an authoritative catalog requires explicit operator intervention.

| Failure/termination boundary | Recovery and live policy |
| --- | --- |
| Partial log frame/index or rotation header | Writer fails; old checkpoint protects prefix; discard only proven tail |
| Complete frame before/after log force, before directory sync | No success; old checkpoint; tail may be discarded |
| Segment names synced, before checkpoint force | Old checkpoint remains authority; tail may be discarded |
| Partial/forced checkpoint temporary file | Ignore temporary; preserve authoritative checkpoint; remove scratch after validation |
| Atomic checkpoint replacement before directory sync | Either old/new checkpoint may survive; both refer only to previously forced data |
| Checkpoint directory synced, before coordinator publication/response | Record must recover; caller may observe unknown outcome |
| Publication before response or lost response | Record recovers exactly; retry may append a duplicate |
| Root marker forced, before initial catalog installation | Fail closed; bootstrap needs operator intervention, never infer an empty replacement catalog |
| New topic files before catalog installation | Valid catalog proves next slot unpublished; cleanup is repeatable |
| Catalog replacement/sync before topic response | Topic may survive without acknowledgement; duplicate creation rejects |
| Published corruption or missing metadata | Fail closed, preserve files; never infer a smaller boundary |
| Failure during tail repair | Keep checkpoint unchanged; next recovery repeats validated repair |
| Timeout while any operation is executing | Degrade; retain thread/locks; resolve late completion once; explicit close/reopen required |

## Admission, scheduling, timeouts and close

Durable defaults: 32 topics, 16 partitions/topic, 64 total partitions; hard ceilings
128/64/128. Holding all logs open bounds storage channels to 3*totalPartitions+4
(root lock, per-partition locks/log/index, one temporary metadata or read pair).
KF-003 segment/frame/read limits remain configurable and enforced. A directory scan and
orphan cleanup are bounded by these limits and maxSegments; no recursive unbounded walk.

Default 128 admitted calls and 16 MiB aggregate key/payload bytes; hard ceiling 1024 calls
and 64 MiB. Empty requests count. Topic/inspection operations count too; inspection reserves its requested return-byte budget. Admission occurs
before queueing broker-owned work; immutable caller command allocations and inbound gRPC
buffers are excluded. Returned test inspection snapshots are bounded separately at 128
records/2 MiB and are caller-owned after completion. No bound on total JVM heap is claimed.

One bounded request queue, one-slot writer queue, one completion slot, two response workers
with queue bounded by admitted calls. No request-specific threads or unbounded executors.
Keep a reservation until both underlying work is resolved and response handling finishes.
Client cancellation/deadline cannot release executing/queued work. Each reservation releases
exactly once. Callbacks can block response workers but cannot block coordination or inflate
queues beyond admission; exhaustion rejects immediately.

Use injected monotonic nanoseconds for watchdogs (default 30 seconds) and wakeable bounded
coordinator polling. Diagnostics are immutable volatile snapshots; no disk I/O or queued
diagnostic callback is needed. Watchdog enters DEGRADED, closes new admission, rejects queued
mutations and reports active client outcome unknown, while retaining its reservation until
the writer resolves. Late success updates P/routing in the same epoch, but cannot send a
second response. Late failure also resolves exactly once. Never automatically become READY
after a watchdog; explicit orderly close and validated reopen are required.

Shutdown changes to CLOSING, stops admission, rejects undispatched work, and waits for the
active writer. Its late success still publishes. Close is executed by the writer only after
work resolves; then release locks and transition CLOSED. A bounded caller shutdown wait may
time out and must report incomplete cleanup; it neither interrupts the writer nor unlocks
the directory. Epoch/operation checks reject mismatched completions. No competing writer.

## Transport and validation

Spring `kaflow.durable.enabled=true` plus `kaflow.durable.directory` explicitly opts in.
Initialization/recovery failure prevents application startup; no volatile fallback.
Bound gRPC inbound messages independently at payload/key maxima plus 1 KiB envelope space.
Four transport workers and a 256-task queue bound service dispatch in opt-in mode. Native
oversized-frame rejection can surface as RESOURCE_EXHAUSTED or a CANCELLED stream reset
with the pinned Netty version; it never reaches append admission or produces success.
Validate exact topic, optional partition/key presence and sizes before command construction.
Map invalid input to INVALID_ARGUMENT, missing topic NOT_FOUND, duplicate topic ALREADY_EXISTS,
limits RESOURCE_EXHAUSTED, closed/degraded UNAVAILABLE and unexpected failures INTERNAL.
Responses expose no paths or exception messages. Transport success follows P advancement.
No Fetch/Commit/Join/Heartbeat/Leave messages, handlers or public inspection endpoint.

Tests use bounded storage/coordinator inspection, controllable I/O events, monotonic clocks,
barriers and bounded subprocess failpoints. Include actual network acknowledgement/restart,
external acknowledgement receipts, lost responses, publication corruption, metadata damage,
safe tail repair/repeated recovery, cancellation accounting and ownership after stalls.
Run fresh source-only build, full tests, prototype smoke and durable packaged restart smoke.
Record all actual failures/retries and distinguish Linux CI from local macOS evidence.
KF-005 handoff: durable group definitions, leased membership and generation fencing.
