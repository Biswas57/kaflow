# KF-003: partition-log storage contract

Status: implemented and validated locally, 2026-10-13.
Design was recorded before production implementation. Source-only build, 120 tests
and packaged smoke passed; [validation evidence](../baseline/kf-003.md).
Working paper: [../../todo.md](../../todo.md). Dependent on unmerged KF-002 `323ec40`.

## Responsibilities and ownership

`kaflow.storage.PartitionLog` owns one qualified `TopicPartition` in one explicitly
chosen directory. This is a storage library, not a broker administration or transport
API. Multiple partitions use different directories. It reuses `ImmutableBytes` and
`StoredRecord`; neither prototype nor in-memory V1 core is wired to storage.

The opening thread is the sole serialized owner of append, boundary capture, read
and close. Calls from another thread fail before I/O, including close. Concurrent
reads are deliberately unsupported. All channel operations use explicit positions;
no shared cursor or executor pipeline is introduced. A paused/running owner cannot
be replaced or have its lock released by a caller's timeout. KF-004 must integrate
writer/completion ownership and bounded read workers separately from coordination.

Storage assigns offsets exclusively, starting at zero across segments. Append takes
an optional immutable key and payload. The injected broker clock assigns Unix-millis
timestamps; repeated keys are new records. `CompletedWrite` reports actual identity,
offset, timestamp and exclusive completed-write end, never durability or publication.
Input validation, overflow checks, frame/receipt allocation and clock access occur
before file modification. Key/payload bytes are never interpreted or transcoded.

## Directories, names and identity

Canonicalize an explicitly selected directory with `toRealPath()` after creation.
Acquire an exclusive OS file lock on `.kaflow.lock` before opening mutable state.
Keep that file permanently: deleting/recreating a lock inode could split ownership.
A process-local canonical-path guard tracks only live file-lock resources and prevents
opening/closing a second lock channel on an already owned inode: some POSIX systems
release process locks when any channel on that inode closes. This guard contains no
broker/log state and is removed on cleanup; OS locks still enforce cross-JVM ownership.
Same-JVM overlap and another JVM's lock failure both report ownership conflict.
The lock channel is separate from all log/index channels. No file lock is a lease.

Layout: `.kaflow.lock`, `00000000000000000000.log`, matching `.idx`, followed by
20-digit zero-padded decimal segment base offsets and the same suffixes. Rebuilding
an index uses `.index-rebuild.tmp` and atomic replacement of the derived `.idx`.
There is no catalog or unchecked topic-derived path: every segment embeds the exact
qualified identity. Opening the same directory as another topic/partition fails.
Strict UTF-8 encoding/decoding rejects malformed surrogates/UTF-8 and noncanonical
replacement; topics remain exact nonblank strings of at most 255 UTF-8 bytes.

Only these names and regular nonsymlink files are allowed within the directory.
Segment bases must start at zero and match the validated preceding exclusive end.
Nonfinal empty segments are invalid; a complete empty final segment is valid.
Orphan indexes and unexpected entries are rejected. A regular stale rebuild temp is
derived state and may be replaced only after all log data validates. New log files
use CREATE_NEW, never truncate or replace. Segment descriptors are bounded in memory.

Supported environment: cooperative processes on a local filesystem supporting Java
FileChannel locks and same-directory atomic rename (Linux reference; macOS development).
Root symlink and `..` aliases resolve to the same owner. Log/index/lock symlinks are
rejected. Network filesystems, external mutation/renaming while open, malicious hard
links and adversarial directory replacement are outside this local ownership model.

## Exact format V1

All integers are big-endian. Unless marked unsigned, lengths/offsets are signed and
must satisfy the bounds below. CRC32C is the Castagnoli checksum with Java's standard
initial/final convention, stored as its low 32 bits. Checksums detect accidental
corruption, not authentication. No Java serialization, encryption or type factories.

Segment header (`32 + T` bytes, `1 <= T <= 255`):

| Byte position | Field | Width / value |
| --- | --- | --- |
| 0 | Magic | 4 bytes, ASCII `KFLG` |
| 4 | Version | unsigned 16-bit, 1 |
| 6 | Reserved flags | unsigned 16-bit, 0 |
| 8 | Total header length | int32, `32 + T` |
| 12 | Topic byte length | int32, T |
| 16 | Partition | int32, nonnegative |
| 20 | Segment base offset | int64, nonnegative, equals filename |
| 28 | Topic | T strict UTF-8 bytes |
| 28 + T | CRC32C | uint32 over all preceding header bytes |

Record frame (`36 + K + P` bytes):

| Byte position | Field | Width / value |
| --- | --- | --- |
| 0 | Total frame length | int32, includes prefix and trailing CRC |
| 4 | Version | unsigned 16-bit, 1 |
| 6 | Flags | unsigned 16-bit; bit 0 means key present, all others zero |
| 8 | Offset | int64, exact expected contiguous offset |
| 16 | Broker timestamp | int64 Unix milliseconds, preserved exactly |
| 24 | Key length | int32: -1 iff absent, otherwise K >= 0 |
| 28 | Payload length | int32, P >= 0 |
| 32 | Key then payload | K bytes (zero if absent), then P bytes |
| 32 + K + P | CRC32C | uint32 over every preceding byte of this frame |

Present-empty keys have flag 1 and length 0; absent keys flag 0 and length -1.
Empty payloads and arbitrary binary bytes are valid. CRC covers length, version,
flags, offset, timestamp and both lengths as well as data. Headers and exact identity are validated on open; frame versions, flags, length
relationships, offsets and CRC are validated on reopen and read. Fixed prefixes are read first; all lengths, remaining-file bounds and
checked arithmetic are validated before variable allocation or positional access.

## Rotation and sparse indexes

The immutable configured segment byte limit **includes the segment header**. A frame
never spans segments. Reject frames too large for an otherwise empty segment before
any write. Exact fits remain in the current segment. Rotate before exceeding the
limit; the next filename/base is the current completed end. Reaching maxSegments
rejects without modification. A rotation I/O error fails the owner; do not reuse or
overwrite a possibly created segment on that instance.

Index header (32 bytes): magic `KFIX` at 0; uint16 version 1 at 4; uint16 flags 0 at 6;
int32 length 32 at 8; int32 sparse interval at 12; int64 base at 16; uint32 segment
header CRC at 24; uint32 CRC32C of bytes 0..27 at 28. Entries are 24 bytes: int64 offset,
int64 frame position, int32 total frame length, uint32 CRC32C of the preceding 20 bytes.
Index the first frame and every Nth frame thereafter relative to that segment's base.

Indexes never establish validity. On open, stream and validate every complete log
frame, comparing each expected sparse entry and final index length. Missing, stale,
wrong-version or damaged indexes are rebuilt only after **all** log segments validate.
Rebuild streams entries to a temporary file and atomically replaces the derived index;
it does not load whole logs/indexes in memory or repair any log byte. Valid indexes and
logs are left byte-for-byte unchanged on clean reopen. Different configured index
intervals cause a rebuild; a smaller incompatible segment/record limit rejects open.

Read lookup binary-searches bounded segment descriptors, then computes the appropriate
sparse entry by ordinal (one 24-byte read), validates its checksum, offset, length and
position against segment bounds, and validates its referenced frame. At most N-1
frames are skipped to reach the requested offset. Invalid indexes encountered after
open fail the owner; reopening can rebuild them from validated logs. Actual I/O errors
and zero progress are not disguised as index corruption. No unbounded linear index scan.

## Completion, reads and failure states

Append loops on short writes using the current explicit position. Eight consecutive
zero-progress transfers (configurable within 1..1024) fail with an I/O error; successful
progress resets that count. Short reads similarly loop; premature EOF is corruption.
These bounds prevent busy-spinning, not an OS call itself blocking indefinitely.

Only after the full frame and any required index entry complete does the in-memory
completed-write boundary advance. Index failure can leave a structurally complete
frame with no successful receipt; its outcome is uncertain until reopen. No force,
fsync, checkpoint or durable acknowledgement occurs. Reopening complete unforced data
may expose writes whose callers observed failure; KF-004 needs publication evidence.

`captureBoundary()` returns an immutable, instance-bound handle with qualified identity
and exclusive completed end. `read(identity, start, maxRecords, maxBytes, boundary)`
validates the handle and request before I/O. Boundaries from another instance are
invalid. Later appends cannot enlarge a captured read. Returned immutable records and
`nextOffset` are independent of future appends/close; reading changes no consumer state.
At/beyond the captured end return empty with nextOffset equal to the requested start.
Zero bytes is a valid byte budget; maxRecords must be positive. Zero-byte records still
count. If the first candidate's key+payload exceeds the budget, throw BUDGET_TOO_SMALL
with no skip; after a partial batch, stop before that frame and return its offset as
continuation. Frames must be complete within the segment's completed byte boundary
and have offset below the captured exclusive end.

OPEN -> FAILED on any mutation/read I/O or corruption error; preserve the original
IOException and reject subsequent I/O with it as the cause. The completed boundary
remains inspectable in FAILED, unchanged by the failed append. Invalid input, record
budget errors and configured capacity exhaustion do not fail an otherwise healthy
owner. No silent truncation, skipping, tail resumption or automatic replacement.

Close runs only on the owner, marks CLOSED, closes all channels and releases the lock;
repeated owner close is harmless. Initialization failure closes acquired resources and
releases ownership. Cleanup errors are suppressed onto the primary error; no primary
failure is masked. There is no asynchronous storage work to outlive close. FileChannel
cleanup is attempted even when another close fails. After close, append/read/boundary capture reject; `state()` remains a diagnostic.

## Resource bounds

Defaults: 16 MiB per segment including header; 1,024 segments; 16 KiB key; 1 MiB payload;
sparse interval 128; read limit 128 records / 2 MiB logical key+payload; 8 zero-progress
attempts. Constructor options permit smaller bounds and enforce hard ceilings of
128 MiB segments, 1,024 segments, 8 MiB combined key/payload, interval 4,096, 128 returned
records, 2 MiB returned logical bytes, and 1,024 zero-progress attempts.

Enumeration rejects more than `2 * maxSegments + 2` entries before unbounded collection.
Log validation plus an optional rebuild scans at most twice maxSegments * segmentBytes,
never beyond checked
file boundaries. Segment metadata is O(maxSegments); indexes are on disk with at most
ceil((segmentBytes-headerBytes)/36/N) entries. Temporary frame/decode buffers are bounded
by `36 + maxKeyBytes + maxPayloadBytes` plus immutable value copies, not entire logs.
Read results have independently bounded logical bytes/counts; skipped-frame decoding
also stays within the per-frame bound. At most five storage file channels are open
(lock, active log/index, a read log/index); enumeration is closed before file scanning.
Caller-held records, file-system cache and total JVM heap are outside these logical bounds.

## Crash states and KF-004 handoff

| Observed state | KF-003 handling | Deferred decision |
| --- | --- | --- |
| Partial header/frame or bad checksum | Live owner fails; reopen rejects, no log repair | Whether an unpublished tail is discardable requires durable publication evidence |
| Complete, unforced frames | Reopen validates and can read them; no durability claim | Force ordering, durable checkpoint/publication boundary and acknowledged-record recovery |
| Rotation creates partial header or gap | Fail closed without overwrite/truncate | Crash-safe segment creation and directory synchronization |
| Complete empty final segment after rotation | Validate and reuse at its base offset | Publication and directory persistence ordering |
| Missing/stale/damaged index or leftover rebuild temp | Validate authoritative log; rebuild only derived bytes | Durable metadata cost and recovery scheduling |
| Storage call stalls or caller stops waiting | Owner and directory lock remain with that instance | Watchdog/degraded readiness and late completion policy; never spawn replacement writer blindly |

KF-004 must define bounded durable checkpoints, file/directory synchronization order,
crash recovery that preserves acknowledged retained data, publication versus completed
writes, and late completions. Clean reopen is not crash or physical power-loss evidence.
V1 Append remains unregistered until that boundary exists. Consumer Fetch/Commit,
leases, long polling, retention, migration and public storage administration are deferred.
