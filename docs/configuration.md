# Startup configuration

All broker options are read once at startup; changes require a clean restart. The supported
quickstart precedence is command-line `--property=value` over environment over external
`application.properties`/YAML over packaged defaults. Java system properties also outrank
environment. An external `application-{profile}` file overrides its corresponding base file;
profile ordering and `spring.config.*` are Spring Boot configuration, not a second broker
configuration mechanism. Prefer one explicit external file and CLI overrides. Packaged smoke
checks exercise external-file → environment → CLI precedence using actual storage-quota metrics.

Use canonical kebab-case property names. Environment uses uppercase, dots to underscores,
and removes hyphens: `kaflow.durable.max-storage-bytes` becomes
`KAFLOW_DURABLE_MAXSTORAGEBYTES`; `kaflow.durable.wait.max-requests` becomes
`KAFLOW_DURABLE_WAIT_MAXREQUESTS`. Lists use an underscored index, e.g.
`KAFLOW_DURABLE_TOPICS_0_NAME`. CLI list keys must be quoted in shells such as zsh.
No wildcard environment export is needed. Unknown fields under `kaflow.durable` fail binding.

## Modes and listeners

| Property | Default | Supported meaning |
| --- | --- | --- |
| `server.address` / `server.port` | `127.0.0.1` / `8080` | HTTP diagnostics; port 1–65535 (0 for ephemeral testing) |
| `grpc.server.address` / `grpc.server.port` | `127.0.0.1` / `9090` | gRPC; port 1–65535 (0 for ephemeral testing) |
| `grpc.server.health-service-enabled` | `true` | Inherited standard gRPC health; not broker readiness |
| `grpc.server.reflection-service-enabled` | `true` | Inherited standard gRPC reflection; schemas/service discovery |
| `kaflow.durable.enabled` | `false` | Explicit `true` selects durable V1; false selects volatile prototype |
| `kaflow.durable.directory` | none | Required, nonblank dedicated writable directory in durable mode |
| `kaflow.durable.topics[i].name` | empty list | Exact nonblank identifier, valid UTF-8, ≤255 encoded bytes, no duplicates |
| `kaflow.durable.topics[i].partitions` | required per entry | 1..configured partitions/topic; fixed once created |
| `kaflow.durable.shutdown-timeout` | `10s` | Duration >0 and ≤5m; shared total broker/transport teardown budget |

Durable mode exposes the `kaflow.v1.Broker` application API plus health/metrics HTTP.
The gRPC starter also enables standard health and reflection services by default in both modes;
set their properties above to `false` to disable them. Standard gRPC health is not connected
to broker degradation; use the documented HTTP readiness endpoint for admission/readiness checks. Legacy `/api/**`,
prototype gRPC and prototype state beans are absent. Default prototype mode retains those
legacy interfaces and does not register V1. The standalone prototype CLI/server remain historical
entry points. Neither mode supplies authentication. The release examples configure no TLS;
changing bind addresses does not add access control. There is no public reset/admin mutation API.

## Durable limits

Every suffix below is prefixed by `kaflow.durable.`. Bytes are decimal integer byte counts;
KiB/MiB/GiB in this table describe binary multiples, not accepted numeric suffixes. Counts
bound zero-byte operations too. Duration properties accept `ms`, `s`, `m` or ISO-8601 durations.

| Suffix | Default | Valid range / interaction |
| --- | ---: | --- |
| `segment-bytes` | 16777216 | 69..134217728; topic header plus record must fit |
| `max-segments` | 1024 | 1..1024 per partition; no unbounded segment inventory |
| `retention-bytes` | 0 | 0 disables; otherwise ≤max-partition-bytes, whole closed segments |
| `max-storage-bytes` | 8589934592 | 1..17592186044416; must fit metadata and rotation headroom |
| `max-partition-bytes` | 1073741824 | 1..max-storage-bytes; must fit rotation envelope |
| `storage.max-key-bytes` | 16384 | ≥0; key+payload configured maxima ≤8388608 |
| `storage.max-payload-bytes` | 1048576 | ≥0; combined limit above; segment can impose a smaller frame bound |
| `storage.index-interval` | 128 | 1..4096 records per sparse entry |
| `storage.max-read-records` | 128 | 1..128 per response |
| `storage.max-read-bytes` | 2097152 | 0..2097152 key+payload bytes per response |
| `storage.zero-progress-limit` | 8 | 1..1024 repeated zero-byte file operations |
| `broker.max-topics` | 32 | 1..128 |
| `broker.max-partitions-per-topic` | 16 | 1..64 |
| `broker.max-partitions` | 64 | 1..128 total |
| `broker.max-calls` | 128 | 1..1024 admitted mutation/admin calls |
| `broker.max-bytes` | 16777216 | 0..67108864 admitted key+payload bytes |
| `broker.watchdog` | `30s` | >0..1h; unresolved storage/read timeout degrades, not cancellation proof |
| `groups.max-groups` | 128 | 1..256 durable definitions |
| `groups.max-members-per-group` | 32 | 1..64 |
| `groups.max-members` | 256 | 1..1024 overall |
| `groups.max-pending-joins` | 32 | 1..128 |
| `groups.max-controls` | 128 | 1..512 admitted controls |
| `groups.max-control-bytes` | 2097152 | 0..8388608 logical control reservations |
| `groups.max-catalog-bytes` | 131072 | 48..131072; names and definitions must actually fit |
| `groups.lease` | `30s` | 1ms..5m, integral milliseconds; measured from application, not receipt |
| `consumer.read-workers` | 2 | 1..4 |
| `consumer.max-reads` | 16 | 1..128 admitted reads |
| `consumer.max-read-charge` | 67108864 | 0..268435456 logical read budget |
| `consumer.max-progress-calls` | 64 | 1..256 |
| `consumer.max-progress-charge` | 1048576 | 0..8388608 logical progress budget |
| `wait.max-requests` | 256 | 1..1024 |
| `wait.max-per-session` | 16 | 1..max-requests |
| `wait.max-per-partition` | 64 | 1..max-requests |
| `wait.max-metadata-bytes` | 524288 | 0..2097152; 2048 charged per waiter |
| `wait.max-wait-millis` | 30000 | 1..120000; requests may select zero for immediate Fetch |
| `wait.batch` | 8 | 1..32 bounded activation batch |

These are logical resource bounds, not total JVM heap, filesystem free-space or kernel-buffer
limits. Transport has four worker threads and a 256-entry queue. Response executors are bounded
by admission counts. Inbound V1 message size is 1024 + configured maximum key and payload bytes.
Read response metadata also consumes admission charge. Parked Fetch calls retain bounded request
metadata but no read worker, open read channel or read snapshot. Reservations remain until real
reads/writes and response delivery unwind, including after client cancellation.

Startup syntax checks precede directory open. After recovery, the complete provisioning list
is checked for conflicts, total topic/partition bounds, catalog size, segment identity fit and
available managed storage before any missing topic is created. The storage-owner preflight
uses bounded in-memory metadata, not disk scans. Creation then uses normal durable publication
one topic at a time; an I/O failure can leave a durable prefix. Restarting with the same list
completes it idempotently. Strict internal create still rejects an existing topic. Provisioning
is not a transaction. The broker remains unready on any startup failure.

Quota validation reserves a fixed metadata/index/replacement allowance derived from configured
maxima, plus a record/rotation envelope. Very small quotas may fail even on an empty directory.
Lowering limits below recovered data is an error, not permission to discard acknowledged data.
Retention cannot guarantee immediate quota relief while segments are active or pinned.
