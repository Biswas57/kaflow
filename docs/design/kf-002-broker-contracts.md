# KF-002: ownership, byte records, and Append contracts

Status: implemented and validated locally (2026-10-13). The ticket working
paper is [../../todo.md](../../todo.md); executed results are in
[../baseline/kf-002.md](../baseline/kf-002.md). This document specifies the contracts;
passing in-memory tests do not establish durable or successful network Append.

## Migration and ownership

The running prototype retains its existing REST/gRPC behaviour but has ordinary,
injected state. Its composition roots construct a cluster and controller; helpers
and instance-owned factory registries use that same cluster. No global broker
lookup/reset or static factory cache remains. Spring's REST and gRPC adapters share
one injected controller. Separate applications, standalone servers, CLI invocations,
and tests can reuse the same identifiers without sharing state.

The new `kaflow.broker` in-memory core is separate from this prototype. There are
no dual writes, namespace sharing, or migration. It is not a Spring service. The
new `kaflow.v1` Protobuf schema generates classes but has **no registered service
implementation**. An in-memory receipt proves insertion, never durability. Successful
V1 network acknowledgements are prohibited until KF-004 establishes durable publication.

KF-002 implements no persistence, Fetch/Commit, membership leases, subscription work,
retention, or migration. Legacy type/offset/subscription defects remain outside this
ownership refactor. The approved coordinator/storage-writer separation remains
required before disk-backed execution; a short mutation lock is sufficient only for
this bounded in-memory foundation.

## Opaque payloads and immutable values

Producers serialize application objects externally: JSON encoded as UTF-8, Protobuf,
or any binary format. `AppendRequest.bytes payload` carries exactly those bytes.
Protobuf is the transport envelope, not the application serialization format. The
broker never parses, normalizes, transcodes, infers formats, unpacks `Any`, uses Java
payload-type factories or Java object serialization, or stores application-domain objects.
Empty payloads, embedded zero bytes, and invalid UTF-8 are valid. Headers, content-type
metadata, and schema management are deferred; applications agree on serialization.

Example: `Order(orderId="o-42", quantity=2)` becomes producer-owned UTF-8 bytes for
`{"orderId":"o-42","quantity":2}`; a Protobuf Append envelope carries those bytes;
an immutable broker record retains them unchanged. A **future Fetch** envelope will
return the original bytes for the consumer's JSON deserializer. **Future disk storage**
will wrap the same bytes in a versioned binary frame with lengths, metadata, and
integrity checks. Neither disk storage nor Fetch is a KF-002 deliverable.

Immutable byte values defensively copy public constructor input, compare by content,
provide allocation-free lengths, and expose only read-only views or copied arrays.
Commands, records, results, and qualified identities are immutable. An absent key
differs from a present empty key. Topic identifiers are exact nonblank strings of at
most 255 UTF-8 bytes, with no silent normalization. Partitions are fixed at topic
creation, numbered `0..count-1`, and always identified together with their topic.

The synchronous core exposes `createTopic(TopicId, partitionCount)`,
`append(AppendCommand) -> AppendResult`, and idempotent `close()`. Append receipts
contain the actual qualified partition, nonnegative offset, and clock-assigned Unix
millisecond timestamp. Timestamp order is not an ordering guarantee.

## Admission and retained accounting

Caller-created immutable commands may already contain defensive copies; their
construction is outside admission. The broker shares immutable byte values safely,
without taking ownership of mutable caller arrays. Length inspection does not invoke
copying getters. Admission is reserved before additional broker-owned work/retained
metadata is created or the mutation lock is awaited.

Each admitted call counts once and is charged `key.length + payload.length` bytes.
Each stored record likewise counts once and is charged those bytes. Charges apply
per reference even if values are shared or the same command is reused. Zero-byte
records and calls still consume counts. These logical limits exclude caller memory,
caller-held snapshots, future transport buffers, and total JVM heap.

| Constructor-configured resource | Default |
| --- | ---: |
| Topics / partitions per topic / total partitions | 128 / 64 / 1,024 |
| Key / payload bytes | 16 KiB / 1 MiB |
| Admitted calls / aggregate admitted bytes | 128 / 16 MiB |
| Stored records / aggregate retained bytes | 100,000 / 64 MiB |

Exhaustion rejects immediately at admission; retained exhaustion rejects before
mutation rather than evicting records. Checked/subtraction-based arithmetic prevents
counter overflow. Limits describe this V1 core, not the legacy runtime.

Retained accounting starts atomically with insertion. Admission stays reserved until
the append's `finally` block after releasing the mutation lock: temporary overlap is
conservative. Validation before reservation acquires nothing. Every post-reservation
validation, capacity, closure, or exception path releases exactly once. Normal fallible
preparation, including timestamp and receipt construction, precedes committing mutation.
No failed operation advances offsets or routing counters or inserts a partial record.
Fatal VM failure is not a recovery guarantee; KF-002 has no durable storage.

Metadata, routing, insertion, and closure use one broker-owned mutation lock. No
production disk I/O, network observer callbacks, executors, or worker queues operate
inside it. A package-private scheduling seam supports deterministic race/failure tests;
the public constructor uses a no-op seam. Never hold the admission lock while waiting
for the mutation lock. Mutation-lock holders may briefly acquire the admission lock.

## Routing, append, and ordering

1. An explicit partition wins. Invalid explicit partitions fail without fallback.
2. Otherwise a present key selects the unsigned big-endian first eight bytes of
   SHA-256(key), modulo fixed partition count. The empty key is still keyed.
3. Otherwise use a per-topic round-robin counter starting at zero. Only successful
   unkeyed appends advance it; keyed, explicit, and rejected operations leave it alone.

Offsets start at zero independently per partition. Record insertion, next-offset
advancement, retained accounting, and any round-robin advancement form one locked
mutation. Overflow is rejected. Repeated keys are ordinary new records, not deduplication.

Sequential order means synchronous calls from one caller to the same partition,
each completed before the next begins. Overlapping requests, including from the same
logical producer, have no producer-order guarantee. KF-002 has no sequencing protocol.
Concurrent acceptance is ordered by the mutation boundary, not thread starts, wall
clock, or scheduling. No cross-partition order is promised.

Independent fixed vectors (Python hashlib cross-checked against OpenSSL), seven partitions:

| Key | First eight SHA-256 bytes (hex) | Partition |
| --- | --- | ---: |
| Present-empty | e3b0c44298fc1c14 | 1 |
| UTF-8 `order-42` | 3bf8b157c4238eef | 5 |
| Hex `00 ff 80 c3 28` | dc9549aded985d92 | 0 |
| Hex `ff` | a8100ae6aa1940d0 | 2 |

Tests must embed the expected values, not call the production helper to derive them.

## Closure and test inspection

Close linearizes at the closed-state transition under the mutation lock. It closes
admission, drops topic/log references, and zeros retained record/byte accounting.
An append linearized first succeeds; otherwise it rejects without mutation. Calls
already admitted but still waiting recheck closure under the lock and release their
reservations on unwind. Topic creation checks the same closed state under the lock.
Repeated close is harmless. Do not zero outstanding admission counters in close:
each call releases its own reservation, and close need not wait for paused callers.
A successful caller can resume after close; ordering refers to linearization, not
thread scheduling of the return statement.

Dropping references releases the broker's retained memory ownership, not necessarily
physical heap immediately. Immutable values/snapshots held by callers remain valid;
close neither forces garbage collection nor invalidates them.

A package-private inspection seam snapshots one qualified partition from an offset,
at most 128 records and 2 MiB of aggregate key/payload bytes per call. A smaller record
limit is allowed; invalid bounds are rejected. It takes the mutation lock, returns an
immutable list of immutable values, and never exposes live collections or arrays.
If custom limits allow a single record larger than the inspection byte cap, inspection
rejects with resource exhaustion rather than returning an oversized snapshot or
silently skipping it. New record inspection rejects after close; earlier snapshots
stay valid. A separate package-private accounting snapshot remains available after
close to verify closed state, zero retained accounting, and eventual zero admission.
This is a bounded test seam, not public Fetch, consumer progress, or a transport API.

## V1 wire contract and later operations

The generated `kaflow.v1.Broker` service defines only unary
`Append(AppendRequest) returns (AppendResponse)`. Request fields are topic, optional
partition, optional key, and opaque `bytes payload`; response fields are qualified
partition identity, nonnegative `int64` offset, and broker Unix-millisecond timestamp.
No producer registration, type tag, message-ID hash, batching, or success flag exists.
There is no registered service implementation or adapter from volatile receipts to
successful network responses. Missing-service calls return gRPC `UNIMPLEMENTED`.

Future transport mapping: invalid input -> INVALID_ARGUMENT; missing topic -> NOT_FOUND;
duplicate topic -> ALREADY_EXISTS; admission/capacity/offset exhaustion -> RESOURCE_EXHAUSTED;
closed broker -> UNAVAILABLE; unexpected failure -> INTERNAL. Retried ambiguous requests
may duplicate records; there is no idempotent producer or exactly-once claim.

Future Fetch will return bounded original bytes without advancing committed progress.
Commit will record the next offset, monotonically and idempotently, no higher than the
readable durable end, with apply-time session/generation/ownership validation. Admin
resets are separate. Join/Heartbeat/Leave will use one-topic groups, leases, and generation
fencing. Immediate Fetch precedes asynchronous long polling. These semantics are recorded
now; their messages and implementations belong to later tickets.

## Acceptance and validation

Preserve the seven active prototype tests and add independent-instance tests. Exercise
immutable inputs/outputs, binary/empty payloads, key presence, fixed qualified partitions,
exact result-to-record correspondence, literal routing vectors, precedence, rejected
round-robin stability, sequential order, and concurrent unique contiguous offsets.
Test every count/byte capacity separately, including zero-byte work and exception release.
Use barriers to force both close outcomes, verify subsequent closed operations, idempotence,
zero retained accounting, eventual zero admission, and still-valid prior snapshots.
Verify bounded immutable inspection and schema presence/round trips plus absence of any
successful V1 endpoint. Do not reactivate historical commented suites or weaken assertions.

Run a fresh source-only snapshot including uncommitted inputs:

```sh
./gradlew clean :app:classes :app:testClasses :app:bootJar --no-daemon
./gradlew :app:test --rerun-tasks --no-daemon
python3 scripts/smoke_bootjar.py
```

Record actual outcomes, counts, timeouts, report paths, changed files, limitations, and
handoff in the working paper. Mark DONE only after evidence and diff review. No automatic
commit, push, PR, or start of KF-003 is authorized by this implementation request.
