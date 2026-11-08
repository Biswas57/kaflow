# Public V1 examples

Build with `./gradlew :clients:installDist --no-daemon`. The executable is
`clients/build/install/kaflow-client/bin/kaflow-client`. It generates stubs from the canonical
[V1 schema](../app/src/main/proto/kaflow/v1/broker.proto), uses Java 17 and plaintext gRPC.
Follow the [quickstart](../README.md) first. Every option takes a value, including booleans.
The default target is `127.0.0.1:9090`; `--host`, `--port` and `--deadline-ms` override it.
The default per-RPC deadline is 15000ms (1..120000); it must exceed the selected Fetch wait.

```sh
clients/build/install/kaflow-client/bin/kaflow-client produce --topic json-orders --count 2 --key-hex ''
clients/build/install/kaflow-client/bin/kaflow-client consume --topic json-orders --group billing --count 2
clients/build/install/kaflow-client/bin/kaflow-client consume --topic json-orders --group audit --count 1
clients/build/install/kaflow-client/bin/kaflow-client consume --topic json-orders --group billing --count 1 --replay-from 0
```

The producer creates application `Order(orderId="o-42", quantity=2)` data, serializes JSON
as UTF-8 bytes, then sets Protobuf `bytes payload`. The envelope does not prescribe JSON.
The broker retains exactly those bytes; Fetch returns them for application JSON deserialization.
Omitting `--key-hex` means absent; `--key-hex ''` is present-empty. Arbitrary binary keys are
hex, e.g. `00ff80c328`. `--partition 0` takes precedence over keyed routing. Output is actual
qualified partition, offset and broker timestamp. There is no automatic Append retry. A timeout,
lost response or INTERNAL/UNAVAILABLE outcome can follow a durable mutation; retrying may duplicate.
Application identity/deduplication is the application's responsibility.

The consumer joins one topic/group, uses broker-reported committed offsets, and heartbeats on
an independent single thread even while Fetch or application work blocks. Default Fetch is
128 records / 65536 key+payload bytes / 1000ms wait. Options are `--max-records` (1..128),
`--max-bytes` (0..2097152), `--wait-ms` (0..120000, also bounded by server), `--count` (1..100000),
`--seconds` (1..3600, default 30). `--format bytes` accepts opaque binary application data;
`--format json` expects the example's order fields. `--process-delay-ms` (0..60000) demonstrates
independent heartbeat. The loop stops starting work at its time budget; an in-flight RPC may
finish at its RPC deadline, followed by bounded cleanup. Application work checks its budget
between records; partial effects before a timeout can repeat.

Each batch retains immutable original session/incarnation/generation and qualified partition.
A fetched batch that no longer matches the latest received membership is discarded before processing.
That local observation cannot atomically prove that the server still grants ownership.
Reassignment can still happen during application processing; fencing cannot undo those effects.
Commit always uses the batch's original credentials, never a newer generation. Fenced or
ambiguous results report possible duplicate processing; recovery waits for any earlier heartbeat,
requests fresh membership/progress, and rejoins only if the session expired. Ordinary overload
backs off under the overall loop budget. Fetch alone and `--replay-from` never Commit.

Processing before Commit permits duplicate application work after a crash. Committing before
processing would risk losing application work. A successful Commit only confirms a durable next
offset, not application effects. There is no automatic transaction with an external database.

A lagging committed offset below retained start raises OUT_OF_RANGE and stops by default.
After deciding to skip unavailable history explicitly, run the same command with
`--accept-retained-start true`; it logs that decision before reading from the retained bound.
This does not restore deleted data or silently rewrite a group. Subsequent processed records
may be committed normally. There is no supported public inactive-group reset command.

On close the client cancels its outstanding Fetch, shuts down the heartbeat (5s wait), attempts
Leave within the configured RPC deadline, then closes its channel (5s wait). Late membership
responses cannot resurrect it. Failure to leave does not make a session permanent: leases expire.
Cleanup is bounded but does not cancel already-applied durable server work.

`demo` uses a dedicated fresh one-partition `orders` topic and groups `demo-a`/`demo-b`.
It appends two four-byte binary records with present-empty keys, commits two for A and one for B,
checks exact bytes and receipt metadata, then replays without advancing progress. Run it once,
restart the same store, and run it again: start 0/0 → commit 2/1, then start 2/1 → commit 4/2.
Do not mix JSON records into that binary topic; use `json-orders`. Demo inspection is bounded to
128 records, so use a fresh store for a new independent demo rather than running it indefinitely.
