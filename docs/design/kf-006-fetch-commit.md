# KF-006: immediate Fetch and durable consumer progress

Design recorded before production edits, 2026-10-14. Implemented and locally validated:
[working paper](../../todo.md), [326-test and packaged validation evidence](../baseline/kf-006.md).

## Consumer contract

Durable mode adds unary single-partition Fetch(session, generation, qualified partition,
start_offset, max_records, max_bytes) and Commit(session, generation, partition, next_offset).
No implicit lease renewal: continue Heartbeat. Fetch returns original immutable records,
next_offset and captured published_end. At end it returns empty with unchanged continuation;
negative bounds are INVALID_ARGUMENT, beyond end is OUT_OF_RANGE. Count must be 1..128,
bytes 0..2 MiB (or lower configured storage limits). Key plus payload consumes byte budget;
zero-byte records still consume count. An oversized first record rejects RESOURCE_EXHAUSTED;
a later oversized record ends the batch without skipping it. Absent and present-empty keys
remain distinct. No successful partial batch conceals corruption encountered during reading.

Fetch does not change progress. Processing offsets 0–9 then Commit(10) persists the next
position 10; merely fetching those offsets leaves progress unchanged. Commit returns effective
durable_next_offset: equal/lower valid requests return the existing position; higher requests
must be no greater than the published durable end. Every request, including equal/lower,
requires current ownership. No delivery tracking proves processing or a previous Fetch.
Independent groups have independent positions; explicit earlier Fetch offsets enable replay
without resets or rewinding commits. Restart discards sessions, and rejoin uses a new incarnation.
Join/Heartbeat retain existing qualified `assignments` and add packed `committed_next_offsets`,
with exactly the same length and order as assignments. This avoids duplicating partition identities.
A new owner resumes at those durable positions, never another member's Fetch cursor.

## Read ownership and response boundary

Only the writer inspects mutable PartitionLog state. After recovery/topic creation/Append it
issues an immutable snapshot containing qualified identity, exclusive end and immutable segment
base/end/byte/checksum descriptors. The coordinator publishes it with the durable receipt.
Fetch validates ownership and captures that snapshot on coordination; later Append cannot
expand it. A per-request handle references the snapshot until the read actually unwinds.
Read workers use independent READ-only positional channels, at most a log/index pair per
worker, closing both on every path. No shared mutable log/channel state or filesystem work
on coordination. Headers, index entries and complete frame checksums/identities/offsets validate
under captured byte/end boundaries. No deletion in this ticket; handle lifetime plus shutdown
waiting establishes the seam for future segment pins.

Read completion returns to coordination for lifecycle, cancellation, incarnation/session,
lease, generation and assignment revalidation. That check authorizes a response and is its
linearization point; subsequent reassignment cannot revoke authorized/delivered bytes.
Callbacks/serialization run on bounded response workers. Explicit transport delivery callbacks
hold the broker reservation until conversion/onNext finishes; transport buffers after handoff
remain outside logical admission. Caller-held immutable results are caller memory.
Corrupt reads latch DEGRADED and return sanitized DATA_LOSS; I/O failures latch degradation.

## Durable representation and compatibility

V1/V2 catalogs remain readable without rewriting at open. First effective Commit/reset creates
`progress/`, installs zero progress files for all existing groups, synchronizes them and the
directory, then atomically publishes catalog V3. V3 uses the V2 layout with version 3 and makes
all group progress files required. Older KF-005 binaries reject V3. Until V3 is authoritative,
only validated zero-valued upgrade scratch may be removed; never infer committed progress from
scratch. Recovery validates all authority before log repair or cleanup. Missing V3 progress,
corruption, wrong identities, unsupported versions or offsets beyond recovered log ends fail
closed. Existing never-committed partitions explicitly contain zero. No silent reset to zero.

One file `progress/g-NNNNNNNN.commit` per catalog group slot; identifiers never become paths.
Big-endian KFCO magic, uint16 version 1, flags 0, int32 length, UUID store (16), group slot
(int32), topic slot (int32), partition count (int32), revision (int64), count int64 next offsets,
CRC32C. Length = 52 + 8*count, at most 564 bytes for 64 partitions. Slots bind immutable catalog
identities. Revision starts zero with all-zero offsets, increments on each effective update,
never wraps. Updating one group rewrites only its bounded vector, not all groups or history.
The one-time upgrade writes at most maxGroups bounded vectors plus the bounded catalog;
ordinary Commit/reset writes one vector only. Temporary CREATE_NEW file -> complete write -> force -> atomic replacement -> directory sync ->
writer completion -> coordinator publication -> response. Failpoints name each boundary.
New V3 group creation persists zero progress before its catalog entry; only the one next-slot
zero orphan is removable after authority validation. Recognizable unknown scratch versions reject.
No rollback of acknowledged progress; lost responses/failed force/rename have unknown outcomes.
Checksums detect damage, not malicious rollback of valid old metadata or authentication.

## Application and rebalance barrier

Ordinary pending commits carry their original credentials in the bounded writer-work queue;
queueing does not pin membership. When the single writer is idle, coordination revalidates
cancellation, lifecycle, lease/session/generation/assignment and published offset bound. It
atomically pins the entire group before dispatching to the empty one-slot writer queue. There
is no unrelated disk work ahead of an authorized commit. Group-wide generations require a
group-wide barrier; all partitions in that group share it. Other groups remain independent.

States: UNPINNED -> APPLYING -> UNPINNED on known durable success. I/O/ambiguous failure ->
UNCERTAIN until orderly close/recovery. Watchdog leaves APPLYING pinned while the writer runs.
Late success publishes progress exactly once and resolves the pin; late failure preserves it.
A canceled/deadline-expired response never unpins or releases executing work. Equal/lower commits
still validate at dispatch but need no disk mutation/pin. No automatic readiness recovery.

While pinned, Join rejects UNAVAILABLE. Expiry/Leave invalidate affected sessions immediately
but defer removal/reassignment/generation publication; invalid sessions cannot renew or start
work. Surviving Heartbeats may renew and see the unchanged assignments. New consumer work for
the pinned group rejects UNAVAILABLE. On successful resolution, remove invalid members and
publish one generation transition before admitting more work. No new owner races unresolved
old-owner progress. Shutdown invalidates sessions but retains pin/storage ownership until the
real writer ends; ambiguous completed failure remains fenced until close/reopen.

## Internal reset and lifecycle

reset(group, qualified partition, target) is an internal bounded administration method, with no
network route. Target is 0..published_end. Require no live members and no group pin at admission
and application, then pin before disk work. Backward movement is deliberate and durable. A Join
applied first makes reset reject; reset applied first makes Join reject until resolution. An
uncertain reset never enables activation. Queued reset gives no lasting priority over Join.

Cancellation before application rejects without mutation. Cancellation during reads retains
handles/reservations until unwind; during writes outcome remains unknown. Read watchdogs reject
clients and latch degradation while retaining real workers and ownership. No thread interruption
or replacement of blocked I/O. Close stops admission, drains queued work, waits for all reads and
the active writer before releasing storage locks. Bounded shutdown may report unfinished work.

## Resource bounds

Constructor ConsumerOptions defaults: 2 read workers (hard 4), 16 admitted reads (hard 128),
64 MiB read charge (hard 256 MiB), 64 progress operations (hard 256), 1 MiB progress charge
(hard 8 MiB). Each call counts even when empty. Separate bounded consumer ingress/read-completion
queues and two response workers prevent blocked reads/consumer callbacks consuming control
workers. Queues never exceed admissions; each progress operation reserves 2048 bytes including
identity/result and its bounded vector preparation. At most one storage mutation is applying.

A read reserves 3*requested logical bytes + 512*requested records + 1024 envelope bytes +
3*(36+configured max key+payload) scratch + 64*maxSegments descriptors. This conservatively
accounts for immutable results, framing/decode copies, metadata and wire conversion. Independent
worker count bounds simultaneously open channels (2 each). Current published descriptors are
also bounded by totalPartitions*maxSegments. Release admission only after both actual work and
response delivery finish; no cancellation early release. Caller allocations, caller-retained
snapshots, transport buffers, OS cache and total JVM heap are not covered.

## Validation and boundaries

Preserve prior assertions; extend intentional schema inventory only. Deterministic clocks and
barriers cover both sides of read completion/ownership loss and writer-ready/application races,
barrier expiry/Leave/cancel/timeout/late completion, reset/Join, independent replay/resume, all
capacities and cleanup. Literal binary/key/offset/timestamp correspondence, empty/budget/end,
segment-crossing and simultaneous Append/read prove read boundaries. Metadata corruption,
missing authority, upgrade interruption and process failpoints prove observed recovery cases.
Real gRPC plus packaged two-group Commit/rejoin/resume supplement storage tests. Run source-only
build/package, separate full tests, prototype smoke and actual durable smoke; record all results.

Linux local storage is reference; same force/atomic-rename/directory-sync/device assumptions as
KF-004. macOS development and process-kill tests do not prove physical power-loss durability.
No retention, asynchronous waits, partition batching, transactions or exactly-once semantics.
Publication was subsequently authorized after final validation; no PR/merge or KF-007 work.
KF-007 handoff: bounded asynchronous waiters, lost-wakeup prevention,
cancellation and ownership revalidation without holding read workers while waiting.
