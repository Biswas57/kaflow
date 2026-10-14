# KF-005: durable groups, leases and generation fencing

Implemented and locally validated, 2026-10-14. Design was recorded before code. Status and evidence live in
[the working paper](../../todo.md) and [the validation record](../baseline/kf-005.md).
KF-004 Append publication, data framing, directory ownership and default prototype
isolation remain unchanged. No Fetch, Commit, offsets, replay, resets or retention.

## Client protocol

A group is an exact, nonblank, losslessly encoded UTF-8 identifier of at most 255 bytes,
permanently bound to one existing topic. No identifier becomes a filesystem path.
Join(group, topic, member_label) creates a missing definition durably before allocating
a session. Repeated definition creation with the same topic is idempotent; another
topic fails FAILED_PRECONDITION. An optional descriptive label (0..128 strict UTF-8
bytes) is not an identity or authority; duplicate labels create independent sessions.

Join is not idempotent for membership. Each successful call allocates a fresh opaque
UUID session under a fresh UUID broker incarnation, plus a positive generation,
lease duration in milliseconds and ordered qualified assignments. Session identifiers
are unpredictable broker-issued credentials; neither labels nor knowing a group grants
session authority. Tokens must not be logged, and are not transport authentication.
The caller must protect credentials; this ticket adds no TLS/authentication scheme.

Heartbeat supplies group, incarnation and session. It deliberately does not require
an up-to-date generation: a surviving member can renew and learn the new generation
and assignment after another member joins/leaves/expires. Heartbeat returns the same
session, current generation, assignment and lease duration. Renew comfortably before
the duration elapses, allowing for network and scheduling delay. Duration starts when
the coordinator applies the request, not when the response arrives. No absolute server
monotonic clock value is exposed. An exact deadline is expired (`now - renewed >= lease`).
Wall-clock changes do not affect membership.

Leave supplies the same session credentials, removes only that live session and returns
an empty response. It does not require the latest assignment generation. Duplicate Leave,
expired sessions and prior-incarnation sessions fail FAILED_PRECONDITION; they cannot
remove a new session with a reused label. Unknown groups return NOT_FOUND; malformed
identifiers/credentials return INVALID_ARGUMENT. Capacity/generation exhaustion returns
RESOURCE_EXHAUSTED, binding conflicts and fenced ownership FAILED_PRECONDITION,
closed/not-ready mutation operations UNAVAILABLE, unexpected storage failures INTERNAL.
Descriptions are generic and do not include credentials or filesystem paths.

Cancelled or lost Join responses may leave a durable definition and a live session.
Retry safely discovers the binding but creates another session; lost sessions consume
capacity until expiry. Cancellation never releases executing storage ownership or its
reservation. A creation finishing after watchdog degradation/close publishes its durable
definition but allocates no session; its client receives no second response.

## Assignment and fencing

Each group keeps live members in coordinator Join-application order, independent of
labels, token lexicographic order and thread start order. Visit partitions numerically
0..N-1; partition p belongs to member p modulo live-member-count. Assignments within
a response are sorted by partition. Members may own zero or multiple partitions. Groups
over the same topic have independent generations and ownership.

Start generation at zero with no members. Each successful Join, live Leave or batch of
expired members increments once atomically with the new membership/assignments. Ordinary
heartbeats do not advance it. Generations never wrap. At Long.MAX_VALUE, new joins/leaves
reject before mutation; if expiry requires a transition, fence the entire group, clear
its sessions and require restart (a new incarnation) before any further joins.

A coordinator-applied validation seam checks incarnation, group, live session, current
positive generation, exact topic/partition and current assignment. A prior successful
validation is only an observation, never continuing authority. KF-006 must perform this
check again at operation application. Fencing rejects stale broker operations; it cannot
stop a disconnected consumer from processing bytes it already received.

Expiry is an independent lifecycle event: processing a request first expires its target
group, even if that request will then reject. Beyond such due expiry, rejected requests
never renew, advance generations or mutate assignments. Each periodic pass visits at
most eight groups (at most 64 members each); requests inspect only their bounded group.
One renewal timestamp per live member, no per-heartbeat timer tasks or stale timer heap.

Before KF-006 applies commits, it must install a commit/rebalance barrier: affected
ownership cannot be reassigned while an applying commit's outcome is unresolved. There
are no consumer writes in KF-005; expiry and Leave can therefore reassign during an
Append stall without moving authority over an unresolved consumer operation.

## Durable definitions and compatibility

Extend the existing authoritative `catalog`; do not add a separately optional group file.
KF-004 catalog V1 remains readable with zero groups. First new group atomically installs
catalog V2. Subsequent topic creation preserves groups. KF-004 binaries reject V2 rather
than silently dropping groups; no downgrade is supported. Existing valid KF-004 stores
open without rewriting their catalog. Missing/damaged catalog/root marker still fails
closed. No inference from temporary files, logs or process-local memberships.

V2 is big-endian: magic KFCA, uint16 version=2, uint16 flags=0, int32 total length,
store UUID (16), int64 generation=topicCount+groupCount, int32 topicCount, int32 groupCount;
then the unchanged topic entries, then group entries (int32 UTF-8 group length, int32
bound topic slot, exact group bytes), trailing CRC32C. Total 48 + topic entries +
sum(8+groupBytes), at most 128 KiB. Require at least one group in V2, unique exact group
identities, valid existing topic slots, bounded counts/lengths and checksum. V1 layout
and partition checkpoints/frames remain unchanged. Recognized temporary catalog V1/V2
can be removed only after authoritative validation; unsupported versions fail closed.

Definition creation uses the one KF-004 writer: construct bounded next catalog;
CREATE_NEW catalog.tmp, complete writes, force, atomic replace catalog, sync root;
return completion; coordinator publishes definition, allocates a session and responds.
Catalog cost is bounded by 128 KiB per new definition, no per-heartbeat or per-Append
rewrite/history scan. Concurrent same-binding attempts serialize and discover the
existing definition without rewriting. Conflicting binding never replaces it.

| Failure boundary | Required outcome |
| --- | --- |
| Before/partial temporary write or before replacement | Old catalog authoritative; no Join success; scratch ignored on recovery |
| Temporary force before atomic replacement | Old catalog authoritative; never promote temporary file |
| Replacement before root directory force | Old/new catalog may survive; no Join success, both independently valid |
| Root directory force before completion/response | Definition recovers, response may be lost |
| Cancel/timeout during write | Unknown outcome; retain writer, lock and reservation until resolution |
| Corrupt/missing authoritative catalog or unknown version | Fail closed, preserve authoritative files |

Restart recovers exact group/topic definitions, zero members and no assignments. A new
broker incarnation fences every prior token even when group generation numbers recur.
Same filesystem/device flush assumptions and process-crash limitations as KF-004 apply.

## Coordination, bounds and lifecycle

Membership state belongs exclusively to the existing coordinator; all catalog I/O goes
to the existing writer. A separate bounded control queue and two bounded response workers
serve Join/Heartbeat/Leave/ownership checks. Each loop processes storage completion,
expiry (eight groups), at most eight controls, lifecycle/watchdog and at most one writer
dispatch. Appends cannot fill control admission or block control response workers.
Callbacks never execute on coordination. These are logical bounds, excluding caller
values, transport buffers and total JVM heap.

Constructor-configured GroupOptions (no added dependency):

| Resource | Default | Hard ceiling |
| --- | ---: | ---: |
| Durable groups | 128 | 256 |
| Live members per group / total | 32 / 256 | 64 / 1024 |
| Pending Join calls (including response delivery) | 32 | 128 |
| Admitted controls (including response delivery) | 128 | 512 |
| Admitted control bytes | 2 MiB | 8 MiB |
| Catalog bytes | 128 KiB | 128 KiB |
| Lease duration | 30 s | 1 ms..5 min |

Control charge includes exact input UTF-8/credential bytes plus a conservative bounded
response allowance: 512 bytes + maxPartitionsPerTopic*(255 + 16) for Join/
Heartbeat, 64 bytes for Leave, plus qualified-partition bytes for validation. Every control counts, including empty labels and
zero-assignment members. Reserve before queueing work. Hold until both underlying work
is resolved and response delivery finishes; release exactly once on every path. The
writer queue accommodates bounded append admission plus bounded pending Join calls.
Definitions, members and response lists are independently bounded. Transport's existing
KF-004 inbound limit and four-worker/256-task dispatch queue remain separate limits.

READY permits joins, heartbeats, leaves and ownership validation. DEGRADED rejects joins
and ownership validation but permits live Heartbeat renewal and Leave; expiry continues.
Heartbeat success reports membership only, never storage readiness. Storage degradation
remains latched; no writer replacement or early directory unlock. Definition writes queued
before degradation reject; a late durable definition is retained without granting a session.
CLOSING stops all admission, invalidates all sessions, rejects undispatched controls and
waits for the actual writer under KF-004 rules. A bounded shutdown timeout must report
unfinished work; reservations for active work release only when it really resolves.

## Validation and handoff

Preserve the inherited 191 tests and both actual packaged smoke scripts. Add independent
literal assignment vectors, capacities, cancellation/response ownership, strict identities,
exact monotonic expiry, stale/apply-time fencing, durable V1/V2 compatibility, corruption
and persistence boundary tests. Use controlled I/O, barriers, clocks and bounded child
processes. Exercise actual gRPC membership and restart invalidation, including default
prototype UNIMPLEMENTED behaviour. Clean source-only build/package, separate full tests,
`python3 scripts/smoke_bootjar.py` and `python3 scripts/smoke_durable.py` are required.
Record real failures and final results. The later user request authorizes commit/push
and exact-commit CI verification; see `todo.md`. No PR, merge or KF-006 work.
