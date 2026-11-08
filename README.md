# Kaflow

Kaflow is a lightweight, single-node durable partitioned event-log broker with a custom,
versioned gRPC API. Producers send opaque application bytes; consumers Fetch records and
explicitly Commit their next offsets. Topics have fixed partition counts, and durable
consumer groups use leased membership and generation fencing.

Kaflow is not Kafka wire/client compatible. It does not provide replication, failover,
transactions, producer deduplication, or exactly-once processing. An Append timeout or lost
response can follow a durable write, so a retry may create a duplicate. Commit does not prove
application processing. Process-restart tests do not prove physical power-loss behavior.

## Quickstart

Use Java 17 and Python 3. Build the server and client, then run the separate server/client
test suites:

```sh
./gradlew clean :app:classes :app:testClasses :app:bootJar --no-daemon
./gradlew :clients:installDist :clients:healthProbeJar --no-daemon
./gradlew :app:test :clients:test --rerun-tasks --no-daemon
```

Start an explicitly durable broker with a dedicated fresh directory and one fixed-partition
topic:

```sh
java -jar app/build/libs/app.jar \
  --kaflow.durable.enabled=true \
  --kaflow.durable.directory="$PWD/local-data" \
  '--kaflow.durable.topics[0].name=orders' \
  '--kaflow.durable.topics[0].partitions=1'
```

In another terminal, check broker-aware readiness and use the public example client:

```sh
curl --fail http://127.0.0.1:8080/actuator/health/readiness
clients/build/install/kaflow-client/bin/kaflow-client demo
```

The demo appends binary records and commits two independent groups. Stop with Ctrl-C and
wait for exit before restarting against the same directory. Full configuration, client
examples, Docker workflow, health and metrics, storage recovery, backups, and benchmark
methodology are in the [operations guide](docs/operations.md).

## Project references

- [Architecture](docs/architecture.md): ownership, thread and storage boundaries, V1 flow,
  durable publication, consumer fencing, formats, and compatibility.
- [Operations](docs/operations.md): setup, configuration, clients, Docker, recovery,
  shutdown, validation smokes, and benchmark commands.
- [Ticket board and evidence index](todo.md): status, active blocker, cleanup dispositions,
  and implementation archive links.
- [V1 Protobuf schema](app/src/main/proto/kaflow/v1/broker.proto): canonical wire fields.
- [Prototype historical README](docs/history/prototype-readme.md): context for the earlier
  volatile Tributary prototype; it is not the durable V1 operating guide.

The durable V1 runtime is opt-in with `kaflow.durable.enabled=true`; it has no volatile
fallback. Default startup remains an isolated in-memory prototype with its legacy REST and
streaming gRPC surface. `InMemoryBroker` is a separate contract core and is not registered
as a network service. The two runtimes do not share or dual-write state.

The supplied client and examples use plaintext without authentication. Keep the default
loopback setup local or add independently managed network protection before remote access.
