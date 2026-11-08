# Reproducing measured workloads

Build the broker and clients using the [quickstart](../README.md), then run these separately
from functional tests or other load:

```sh
python3 scripts/benchmark_release.py --mode correctness
python3 scripts/benchmark_release.py --mode measured --config benchmarks/release.json
```

Correctness uses eight operations per scenario and includes deliberate registration-setup and
receipt-cap failures. It validates the harness; its throughput is not a performance result.
Measured mode creates a fresh native broker/store per trial, runs 10s warmup and 30s measurement,
and repeats each of six workloads three times. [Configuration](../benchmarks/release.json) fixes
256-byte payloads, 16-byte keys, four partitions and retention disabled; default server limits
apply. Client and server each use `-Xms64m -Xmx512m`, run on the same host, and may contend.
The source manifest, environment, logs, exact outcome counts, histograms, receipt ledger and
verification results are retained under `app/build/reports/benchmarks/{mode}/run-*`.

| Workload | Concurrency | Measured operation |
| --- | --- | --- |
| Append | 1 and 4 | One public unary Append through the normal force/publication protocol |
| Fetch | 1 and 4 | Immediate public Fetch, max 128 records/65536 key+payload bytes; preseed 128/partition outside timing |
| Consumer | 1 | One durable Append + one-record Fetch/metadata touch + durable Commit cycle; not pure Commit throughput |
| Long-poll | 1 | One isolated proven-parked Fetch, triggered by Append; cycle throughput and delivery latency are distinct |

Fetch repeatedly starts at offset zero in each partition; this is a warm retained-page workload,
not a scan of an ever-growing corpus. The JSON field `successfulRpcsPerSecond` counts measured
operations: a consumer cycle contains three RPCs and a long-poll cycle contains two. It does
not count every heartbeat or setup RPC.

Each Fetch worker has its own group (one or four per phase); consumer and long-poll use one
group per phase. Append has no consumer group. Warmup and measurement use separate topics/groups;
verification adds its own bounded groups after timing. All server limits are the configuration
reference defaults in the supplied runner.

Loads are closed-loop: one outstanding measured operation per worker, no independent open-loop
arrival schedule. Report actual issued operations / window seconds as offered load. Server
backpressure, the loopback client, serialization, fsync and shared-host scheduling can all limit
these rates. There is no claim of production capacity or comparison with Kafka.

Each payload includes a unique run UUID, sequence and warmup/measurement phase in 29 bytes,
with deterministic filler up to exactly the configured payload size. The bounded receipt ledger
reserves before dispatch, including in-flight Append work: at most 100000 entries and 128MiB by
default (hard byte ceiling 256MiB), charged512 + twice key/payload length per entry. It retains
expected bytes, key presence, qualified partition, offset and timestamp. No per-record logging
or disk verification runs in the timed interval. Caller/transport/JVM memory is additional.

For parked delivery, an isolated broker's ordinary aggregate parked gauge must begin at zero.
The harness issues exactly one Fetch, observes parked=1 within 2s, verifies it remains outstanding,
then dispatches Append. Server wait is 10s; RPC deadline 15s. The delivery interval starts at Append
dispatch and ends at Fetch completion; Append latency is also reported separately. Gauge probes
are outside timing. A missing registration is a SETUP failure, never a parked-delivery sample.
There is no test-only endpoint. Setup and cleanup still affect cycle throughput.

Measurement stops issuance at the cutoff. In-flight calls drain for a separate 15s deadline.
In-window OK throughput counts only completions observed by the cutoff, divided by actual window
duration; late successes do not inflate it. Drain outcomes and latencies retain original dispatch
time and are reported separately. Attempted, successful, rejected/status-coded failures and
unresolved/censored counts are explicit; failed/timeout Append may still have persisted. All-status
and successful-only latency histograms are separate. Setup failures have no operation latency.
A prematurely aborted window is labeled; it is not a successful fixed-duration run.

Latency uses 1024 fixed logarithmic histogram buckets, 16 subdivisions per power of two. p50/p95/p99
are bucket upper bounds (≤6.25% relative quantization plus1ns), with exact count/min/max/mean.
This bounds memory without sampling away failures. Timer resolution, GC and scheduling remain
measurement limitations. Successful-only latency is not the latency of the whole workload.

Outside timing, public Fetch checks every acknowledged record against its exact expected bytes,
key presence/key, qualified identity/offset/timestamp. Other persisted run records are counted
separately from acknowledgements. Rejoined consumer groups verify durable committed progress.
Verification is bounded by the ledger, per-page Fetch and a120s deadline. Receipt-cap, quota-cap,
setup, operation, drain and verification failures remain in the report. Unresolved draining or
incomplete verification sets `validated=false` and exits unsuccessfully; errors are not dropped.

CI runs only short correctness. Repeated measured workloads are local evidence, not a CI
throughput gate. Actual final-source measurements and environment are recorded in
[KF-009 validation](baseline/kf-009.md); no result should be inferred from this method alone.

## Observed native results — 2026-11-08

Apple M4 Pro / 24GiB RAM / macOS 26.6.2 arm64 / internal APFS solid-state storage,
shared-host Java 17 broker and client; three 10s-warmup/30s-measurement trials per row.
All 18 trials verified; source manifest
`785879f09c3b1235f47194a0bda6e7434dd4c70347994f83610672c166a4a910`.

| Workload | Callers | Ops/s min / median / max | p50 range (ms) | p95 range (ms) | p99 range (ms) |
| --- | ---: | --- | --- | --- | --- |
| Durable Append | 1 | 45.9 / 52.9 / 56.9 | 17.826–20.971 | 23.069–30.409 | 27.263–44.040 |
| Durable Append | 4 | 54.9 / 55.0 / 57.2 | 71.303–75.498 | 79.692–83.886 | 88.080–88.080 |
| Warm-page Fetch | 1 | 2029.6 / 2146.0 / 2188.1 | 0.459–0.475 | 0.491–0.655 | 0.655–0.983 |
| Warm-page Fetch | 4 | 5322.8 / 5355.9 / 5425.0 | 0.721–0.721 | 1.016–1.049 | 1.245–1.376 |
| Append–Fetch–Commit cycle | 1 | 33.4 / 33.4 / 36.2 | 27.263–30.409 | 35.652–35.652 | 37.749–39.846 |
| Proven-parked delivery | 1 | 48.7 / 51.8 / 54.4 | 14.680–16.777 | 19.923–20.971 | 20.971–23.069 |

Percentiles are per-trial bucket-upper-bound ranges. Long-poll latency measures delivery,
while its throughput counts full setup/trigger/drain cycles. Fetch is a warm-page workload.
No measured errors or unresolved work; 33 post-cutoff successes are excluded from throughput.
See the [validation record](baseline/kf-009.md) for exact totals, environment/probe limitations,
report locations and distinctions from CI. These results are not production capacity estimates.
