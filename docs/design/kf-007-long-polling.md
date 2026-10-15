# KF-007: bounded asynchronous Fetch waiting

Recorded before production changes, 2026-10-16. Status/evidence: [working paper](../../todo.md).

## Client contract

Unary Fetch adds signed `int64 max_wait_millis = 7`. Omitted/zero preserves KF-006.
Negative or above configured maximum is INVALID_ARGUMENT. Existing published data reads
immediately; beyond-end remains OUT_OF_RANGE. Only exactly-at-end requests park. Fetch
never commits or renews a lease; clients continue Heartbeat. No subscription, batching
threshold, waiter identity or second RPC. A successful wait expiry returns empty with
unchanged continuation and the coordinator's published end after fresh fencing checks.

Wait time starts when the broker reserves positive-wait admission, using injected monotonic
nanoseconds. Compare elapsed subtraction with a bounded converted duration (at most 120 s),
including signed nanoTime wrap; no wall clock or overflowing absolute deadline addition.
Assume elapsed intervals below 2^63 ns, as for the existing lease/watchdog clocks. Ingress
delay consumes the original wait; rechecks never restart it. Waiting limits time waiting
for data, not disk reads or delivery. Read watchdogs remain independent. Native gRPC deadlines
are never extended or converted to successful wait expiry: early cancellation is visible
before installation and signals coordination; gRPC reports its own CANCELLED/DEADLINE_EXCEEDED.

## Ownership and transitions

ADMITTED -> WAITING at end; ADMITTED or WAITING -> RUNNABLE -> READING when data is available;
WAITING -> RESPONDING on valid empty expiry; any pre-read state -> RESPONDING on rejection.
READING -> RESPONDING after coordinator revalidation. RESPONDING -> TERMINAL only when
underlying work AND delivery unwind. A canceled future/watchdog client completion is separate
from these cleanup states. Already-authorized bytes cannot be revoked by later reassignment.

The coordinator alone owns the waiter registry: an insertion-ordered bounded timer/sweep
map, partition index and full SessionIdentity index. Parked entries own only credentials,
qualified partition, bounded limits, elapsed-time origin and callback/future references.
They have no read reservation, snapshot, handle, channel, decoded records or writer task.
Removal deletes every index/timer entry; no tombstones or per-waiter timer threads.

Admission has separate per-session/partition counts held through delivery, so callbacks
cannot evade limits by rapidly replacing retired parked requests. The parked indexes can
be removed promptly even while the corresponding admitted metadata remains charged.

## Publication, scheduling and tie decisions

Publication inspection, registration and notification all happen on the coordinator. Check
the current published boundary and register without any interleaving writer publication.
Writer completion advances the boundary and coalesces a partition notification before Append
response delivery. Admission, writes and force alone do not notify. Notifications hold one
partition key each; round-robin partitions visit one eligible oldest waiter per turn, then
requeue the partition only while more eligible entries remain. No duplicated read activation.

Each coordinator iteration handles at most the configured batch of notifications and a
separate bounded maintenance batch. Existing bounded ingress/control/completion processing
continues every iteration. A hot partition cannot cause an unbounded enumeration or duplicate
wake events; other notified partitions rotate fairly. Runnable activation is transient (one
call at a time), with no additional persistent runnable queue. If read count/bytes are full,
activation rejects RESOURCE_EXHAUSTED; overload never masquerades as an empty successful Fetch.
This bounds work and gives fair decision opportunities, not guaranteed success under overload.
Immediate Fetch retains its independent admission opportunity and overload rules.

The removable timer slots are visited round-robin in bounded batches on the existing <=10 ms
coordinator maintenance cadence. They inspect metadata only, never disk. At defaults a full
registry needs at most 32 such iterations for a maintenance sweep, excluding scheduling and
other bounded work; no exact wall-clock delivery promise. Cancellation signals the coordinator
without enqueueing another request; completion capacity cannot be consumed by ingress.

At a decision: cancellation/closure/degradation and current membership validation take
precedence over successful delivery. Already-applied publication is visible before timeout
checks: if valid and data exists at expiry, activate (or explicitly reject overload); otherwise
authorize empty. A publication still awaiting coordinator application follows that decision.
Controls already applied before the decision fence it; controls arriving later cannot revoke
an authorization. Lease expiry is evaluated using current injected time at each validation.

## Membership, barriers and lifecycle

Validate incarnation/session/generation/assignment/lease at initial coordinator admission,
activation and response authorization. Maintenance also invalidates parked requests promptly
in bounded batches. Heartbeat changes neither Fetch credentials nor its original deadline.
Leave/expiry/generation change rejects stale requests; no transfer to another member.

Positive-wait Fetch captures the group's barrier marker at initial validation. Each pin
replaces this immutable identity marker. A request that spans a pin rejects UNAVAILABLE even
if persistence resolves before its next maintenance/activation pass; stale membership still
reports FENCED first. Thus parked/queued requests cannot slip through a transient applying
barrier. Fetch cancellation never resolves or mutates a commit barrier. Existing immediate
Fetch/barrier and late-write publication rules remain intact.

Here queued means a read already validated and waiting for a read worker. Ingress that has
not yet received its initial coordinator validation holds no continuing ownership grant:
its first application checks the current pin and captures the current marker. A pin that
has already resolved before that first validation does not prohibit starting new work.

Cancellation before installation is checked at admission; while parked, remove all registry
references; queued/reading work keeps its handle/read reservation until actual completion.
No interrupt/replacement of blocked I/O. Response charges remain until callback/serialization/
handoff completes even if the future was canceled. Respond/release flags make every path
idempotent. Close/degradation stops new admission and drains parked requests in bounded batches
with UNAVAILABLE. Close waits for waiter removal, queued/active reads and writer completion
before storage close; response executors drain afterward, preserving bounded shutdown semantics.

## Constructor limits and accounting

`WaitOptions` is immutable and injectable, independently of `ConsumerOptions`:

| Resource | Default | Hard ceiling |
| --- | ---: | ---: |
| Positive-wait admitted requests (all phases) | 256 | 1,024 |
| Per session / qualified partition | 16 / 64 | 1,024 / 1,024 |
| Wait metadata bytes (2,048 charged per call) | 512 KiB | 2 MiB |
| Maximum server wait | 30,000 ms | 120,000 ms |
| Notification and maintenance batch each | 8 | 32 |

Zero metadata capacity deliberately disables positive waits. Registry/timer entries <=
admitted positive waits; session/partition indexes <= that count; notifications <= indexed
partitions <= fixed broker partition limit. Metadata charge covers bounded UTF-8 identities,
credentials, index nodes, timing/future/callback references and empty/error response allowance.
Actual decoded data additionally reserves the entire KF-006 read charge before acquiring a
snapshot/handle or dispatching work. Read counts/bytes, workers/channels and read-completion
queue retain KF-006 bounds. No parked wait consumes those bounds.

Consumer ingress and the two-worker response executor are bounded by immediate-read + progress
+ positive-wait admission capacities. Each admitted call guarantees one response slot; a
separate read-completion queue remains bounded by read admission. Admission bookkeeping never
releases merely because a future is observable. Read charge and positive-wait metadata release
exactly once after resolved work and delivered response, conservatively overlapping when active.
Exclude caller-owned values, transport buffering after handoff, OS cache and total JVM heap.

## Tests and packaged evidence

Use injected time/barriers and bounded accounting observation to prove registration and cleanup,
publication ordering, expiry ties, cancellation at each stage, fences/barriers, fair decisions,
every independent count/byte bound, repeated cycles and many parked requests with zero readers.
Real gRPC tests cover native deadlines/cancel and ownership loss. Existing assertions remain.
The packaged smoke retains its two executable-JAR crash/restart phases and adds a separate
fixture loading production classes/dependencies extracted from that actual JAR. It starts the
real Spring runtime on ephemeral ports, observes registration through a package-private bounded
broker seam, then appends and checks exact public Fetch bytes and unchanged committed progress.
No production test endpoint or test class is packaged. Distinguish this observation fixture
from the two unmodified `java -jar` process launches in the evidence.

Run clean source-only build/package, separate full tests and both existing smoke scripts.
Record actual counts/failures/timeouts, provenance and review in `docs/baseline/kf-007.md`.
No disk-format change or new durability claim. Linux local storage remains reference;
macOS/process-kill tests are not power-loss proof. Publication was subsequently authorized
after local acceptance; see the working paper. No PR/merge or KF-008 work.
KF-008 handoff: retention boundaries, out-of-range semantics, segment pins, crash-safe deletion,
quotas, overload and bounded shutdown under load.
