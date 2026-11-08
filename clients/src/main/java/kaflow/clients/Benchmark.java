package kaflow.clients;

import io.grpc.Status;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import kaflow.v1.*;
import org.json.*;
import static kaflow.clients.BenchmarkSupport.*;

/** Closed-loop public-gRPC harness. Every published result carries its verification status. */
final class Benchmark {
    private enum Failure { SETUP, RECEIPT_CAP, QUOTA, OPERATION, DRAIN, VERIFICATION }
    private static final Set<String> OPTIONS = Set.of("host", "port", "http-url", "output", "scenario", "mode",
            "duration-ms", "warmup-ms", "concurrency", "payload-bytes", "key-bytes", "partitions",
            "max-receipts", "ledger-bytes", "count", "setup-ms", "wait-ms", "drain-ms", "deadline-ms");
    private final Arguments args;
    private final String scenario, mode;
    private final int concurrency, payloadBytes, keyBytes, partitions, count;
    private final long durationMillis, warmupMillis, deadlineMillis, setupMillis, waitMillis, drainMillis;
    private final UUID run = UUID.randomUUID();
    private final Ledger ledger;
    private final AtomicLongArray failures = new AtomicLongArray(Failure.values().length);
    private final ConcurrentHashMap<String, Long> commits = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final Path output;
    private final JSONObject report = new JSONObject();

    private Benchmark(Arguments args) {
        this.args = args; scenario = args.text("scenario", "append"); mode = args.text("mode", "correctness");
        if (!Set.of("append", "fetch", "consumer", "long-poll").contains(scenario)
                || !Set.of("correctness", "measured").contains(mode)) throw new IllegalArgumentException("scenario/mode");
        concurrency = (int) args.number("concurrency", 1, 1, 4);
        if ((scenario.equals("consumer") || scenario.equals("long-poll")) && concurrency != 1)
            throw new IllegalArgumentException("consumer and long-poll scenarios use one processing caller");
        payloadBytes = (int) args.number("payload-bytes", 256, HEADER, 1048576);
        keyBytes = (int) args.number("key-bytes", 16, -1, 16384);
        partitions = (int) args.number("partitions", 4, 1, 16);
        if (payloadBytes + Math.max(0, keyBytes) > 65536) throw new IllegalArgumentException("record must fit harness 64 KiB Fetch budget");
        durationMillis = args.number("duration-ms", mode.equals("measured") ? 30000 : 10000, 1, 300000);
        warmupMillis = args.number("warmup-ms", mode.equals("measured") ? 10000 : 0, 0, 60000);
        deadlineMillis = args.number("deadline-ms", 15000, 100, 120000);
        setupMillis = args.number("setup-ms", 2000, 1, 10000);
        waitMillis = args.number("wait-ms", 10000, 1, 120000);
        drainMillis = args.number("drain-ms", 15000, 1, 120000);
        if (waitMillis < 3 * setupMillis || deadlineMillis < waitMillis + 1000)
            throw new IllegalArgumentException("wait must comfortably exceed setup; RPC deadline must exceed wait");
        count = (int) args.number("count", mode.equals("correctness") ? 8 : Integer.MAX_VALUE, 1, Integer.MAX_VALUE);
        ledger = new Ledger((int) args.number("max-receipts", 100000, 1, 100000), args.number("ledger-bytes", 128L << 20, 1, 256L << 20));
        output = Path.of(args.text("output", "benchmark-result.json"));
    }
    static void run(String[] argv) throws Exception {
        var benchmark = new Benchmark(new Arguments(argv, 1, OPTIONS));
        benchmark.execute();
    }
    private void fail(Failure kind) { failures.incrementAndGet(kind.ordinal()); }
    private boolean failed() { for (var kind : Failure.values()) if (failures.get(kind.ordinal()) != 0) return true; return false; }

    private void execute() throws Exception {
        report.put("runId", run.toString()).put("scenario", scenario).put("mode", mode).put("load", "closed-loop")
                .put("concurrency", concurrency).put("partitions", partitions).put("payloadBytes", payloadBytes).put("keyBytes", keyBytes)
                .put("fetchMaxRecords", 128).put("fetchMaxBytes", 65536).put("warmupMillis", warmupMillis).put("measurementMillis", durationMillis)
                .put("setupMillis", setupMillis).put("serverWaitMillis", waitMillis).put("drainMillis", drainMillis)
                .put("java", System.getProperty("java.runtime.version")).put("os", System.getProperty("os.name"))
                .put("architecture", System.getProperty("os.arch")).put("processors", Runtime.getRuntime().availableProcessors())
                .put("maxHeapBytes", Runtime.getRuntime().maxMemory())
                .put("measurementBoundary", "successful operation completions inside window; drain completions excluded from throughput")
                .put("consumerWorkload", "one Append + one-record Fetch/application touch + effective durable Commit per cycle")
                .put("longPollBoundary", "operation waits for Append receipt and Fetch; parkedDeliveryLatency ends at Fetch callback independently of Append acknowledgement")
                .put("placement", "generator shares host with broker in supplied native runner; generator may limit throughput");
        var rpc = new GrpcClient(args.text("host", "127.0.0.1"), (int) args.number("port", 9090, 1, 65535), deadlineMillis);
        try {
            if (scenario.equals("fetch")) { seed(rpc, "warm"); seed(rpc, "bench"); }
            if (warmupMillis != 0) report.put("warmup", phase(rpc, "warm", (byte) 0, warmupMillis, Integer.MAX_VALUE));
            report.put("measurement", phase(rpc, "bench", (byte) 2, durationMillis, count));
            report.put("verification", verify(rpc));
        } catch (Exception e) {
            if (e instanceof CapReached) fail(Failure.RECEIPT_CAP);
            else if (Status.fromThrowable(Main.cause(e)).getCode() == Status.Code.RESOURCE_EXHAUSTED) fail(Failure.QUOTA);
            fail(Failure.VERIFICATION); report.put("verification", new JSONObject().put("passed", false).put("failureType", Main.cause(e).getClass().getSimpleName()));
        } finally {
            try { rpc.close(); } catch (Exception e) { fail(Failure.DRAIN); }
            var counts = new JSONObject(); for (var kind : Failure.values()) counts.put(kind.name(), failures.get(kind.ordinal()));
            report.put("failures", counts).put("validated", !failed()).put("ledgerChargedBytes", ledger.chargedBytes());
            Path parent = output.toAbsolutePath().getParent(); Files.createDirectories(parent);
            writeLedger(output.resolveSibling(output.getFileName() + ".receipts.jsonl"));
            Files.writeString(output, report.toString(2) + "\n");
            System.out.println(new JSONObject().put("result", output.toString()).put("validated", !failed()));
        }
        if (failed()) throw new IllegalStateException("benchmark has failed/incomplete acceptance; inspect result");
    }
    private Entry reserve(String topic, byte phase, int partition) { return ledger.reserve(run, phase, topic, partition, payloadBytes, keyBytes); }
    private AppendResponse append(GrpcClient rpc, Entry entry, Window window) {
        long began = System.nanoTime(); entry.issued = true;
        try { entry.receipt = rpc.append(entry.request); entry.outcome = Status.Code.OK; return entry.receipt; }
        catch (RuntimeException e) { entry.outcome = Status.fromThrowable(e).getCode(); throw e; }
        finally { if (window != null) window.append.add(System.nanoTime() - began); }
    }
    private void seed(GrpcClient rpc, String topic) {
        // Fixed public-API corpus; all its acknowledged records are in the same bounded ledger.
        for (int partition = 0; partition < partitions; partition++) for (int i = 0; i < 128; i++) append(rpc, reserve(topic, (byte) 1, partition), null);
    }
    private JSONObject phase(GrpcClient rpc, String topic, byte phase, long millis, int limit) throws Exception {
        var window = new Window(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(millis));
        var issued = new AtomicInteger();
        var workers = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(concurrency),
                r -> { var thread = new Thread(r, "benchmark-worker"); thread.setDaemon(true); return thread; });
        for (int i = 0; i < concurrency; i++) {
            int worker = i;
            workers.execute(() -> {
                try {
                    if (scenario.equals("append")) loop(rpc, null, topic, phase, worker, issued, limit, window);
                    else try (var session = new ConsumerSession(rpc, "benchmark-" + topic + "-" + worker, topic, deadlineMillis)) {
                        loop(rpc, session, topic, phase, worker, issued, limit, window);
                    }
                } catch (Exception e) { fail(Failure.OPERATION); window.abort(System.nanoTime()); }
            });
        }
        workers.shutdown();
        while (System.nanoTime() < window.cutoff.get()) {
            if (mode.equals("correctness") && workers.isTerminated()) { window.cutoff.set(System.nanoTime()); break; }
            TimeUnit.MILLISECONDS.sleep(Math.min(10, Math.max(1, TimeUnit.NANOSECONDS.toMillis(window.cutoff.get() - System.nanoTime()))));
        }
        window.atCutoff();
        if (!workers.awaitTermination(drainMillis, TimeUnit.MILLISECONDS)) {
            window.unresolved(); fail(Failure.DRAIN);
            rpc.close(); // Client cancellation does not prove the server did not persist the request.
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) fail(Failure.DRAIN);
        }
        return window.json();
    }
    private void loop(GrpcClient rpc, ConsumerSession session, String topic, byte phase, int worker,
                      AtomicInteger sequence, int limit, Window window) throws Exception {
        int nextPartition = worker % partitions;
        while (System.nanoTime() < window.cutoff.get() && sequence.getAndIncrement() < limit) {
            int partition = nextPartition++ % partitions;
            var qualified = TopicPartition.newBuilder().setTopic(topic).setPartition(partition).build();
            if (scenario.equals("long-poll")) {
                if (window.cutoff.get() - System.nanoTime() < TimeUnit.MILLISECONDS.toNanos(setupMillis + 100)) break;
                parked(rpc, session, topic, phase, qualified, window); continue;
            }
            Entry entry = null;
            try { if (!scenario.equals("fetch")) entry = reserve(topic, phase, partition); }
            catch (CapReached cap) { fail(Failure.RECEIPT_CAP); window.abort(System.nanoTime()); break; }
            long began = System.nanoTime(); if (!window.issue(began)) break;
            int records = 0; long bytes = 0; Status.Code status = Status.Code.OK;
            try {
                if (scenario.equals("append")) { append(rpc, entry, window); records = 1; bytes = payloadBytes + Math.max(0, keyBytes); }
                else if (scenario.equals("fetch")) {
                    var batch = session.fetch(qualified, 0, 128, 65536, 0); records = batch.response().getRecordsCount();
                    for (var record : batch.response().getRecordsList()) bytes += record.getPayload().size() + record.getKey().size();
                    if (records == 0) throw new IllegalStateException("empty preseeded corpus");
                } else {
                    var receipt = append(rpc, entry, window);
                    var batch = session.fetch(qualified, receipt.getOffset(), 1, 65536, 0);
                    if (batch.response().getRecordsCount() == 1) entry.delivered = batch.response().getRecords(0);
                    if (!session.process(batch, value -> { /* bounded application touch: byte count below */ }, true)) throw new IllegalStateException("consumer fenced before processing");
                    if (batch.response().getRecordsCount() != 1) throw new IllegalStateException("consumer did not Commit a record");
                    commits.put(topic + ":" + worker + ":" + partition, batch.response().getNextOffset());
                    records = 1; bytes = payloadBytes + Math.max(0, keyBytes);
                }
            } catch (Exception e) {
                status = Status.fromThrowable(Main.cause(e)).getCode(); if (status == Status.Code.OK) status = Status.Code.UNKNOWN;
                fail(status == Status.Code.RESOURCE_EXHAUSTED ? Failure.QUOTA : Failure.OPERATION);
                window.abort(System.nanoTime());
            } finally { window.complete(began, System.nanoTime(), status, records, bytes); }
        }
    }
    private double parkedGauge(long remainingNanos) throws Exception {
        URI uri = URI.create(args.text("http-url", "http://127.0.0.1:8080") + "/actuator/metrics/kaflow.wait.parked");
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(Math.max(1, Math.min(remainingNanos, TimeUnit.SECONDS.toNanos(1))))).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() != 200) throw new IOException("parked gauge unavailable");
            byte[] bytes = body.readNBytes(65537); if (bytes.length > 65536) throw new IOException("metrics response exceeds bound");
            var measurements = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("measurements");
            for (int i = 0; i < measurements.length(); i++) if (measurements.getJSONObject(i).getString("statistic").equals("VALUE"))
                return measurements.getJSONObject(i).getDouble("value");
            throw new IOException("missing parked gauge");
        }
    }
    private void observe(double expected, CompletableFuture<FetchResponse> outstanding) throws Exception {
        long began = System.nanoTime(), budget = TimeUnit.MILLISECONDS.toNanos(setupMillis);
        while (System.nanoTime() - began < budget) {
            if (outstanding != null && outstanding.isDone()) throw new IllegalStateException("Fetch completed before parked observation");
            if (parkedGauge(budget - (System.nanoTime() - began)) == expected) {
                if (outstanding != null && outstanding.isDone()) throw new IllegalStateException("Fetch completed during parked observation");
                return;
            }
            Thread.sleep(2);
        }
        throw new TimeoutException("parked observation deadline");
    }
    private void parked(GrpcClient rpc, ConsumerSession session, String topic, byte phase, TopicPartition partition, Window window) throws Exception {
        CompletableFuture<FetchResponse> fetch = null, observed = null;
        Entry entry;
        try { entry = reserve(topic, phase, partition.getPartition()); }
        catch (CapReached cap) { fail(Failure.RECEIPT_CAP); window.abort(System.nanoTime()); return; }
        var finished = new AtomicLong();
        try {
            observe(0, null); session.heartbeat(); var view = session.view();
            int index = view.getAssignmentsList().indexOf(partition);
            if (index < 0) throw new IllegalStateException("partition not assigned");
            fetch = rpc.fetch(FetchRequest.newBuilder().setSession(view.getSession()).setGeneration(view.getGeneration())
                    .setPartition(partition).setStartOffset(view.getPublishedEndOffsets(index)).setMaxRecords(1).setMaxBytes(65536).setMaxWaitMillis(waitMillis).build());
            observed = fetch.whenComplete((value, error) -> finished.set(System.nanoTime()));
            observe(1, fetch);
            if (fetch.isDone()) throw new IllegalStateException("Fetch no longer outstanding before trigger");
        } catch (Exception e) {
            window.setupFailed(); fail(Failure.SETUP); window.abort(System.nanoTime());
            if (fetch != null) fetch.cancel(true);
            try { observe(0, null); } catch (Exception cleanup) { fail(Failure.DRAIN); }
            return;
        }
        long began = System.nanoTime();
        if (!window.issue(began)) { window.setupCanceled(); fetch.cancel(true); observe(0, null); return; }
        Status.Code status = Status.Code.OK;
        try {
            append(rpc, entry, window);
            var batch = observed.get(deadlineMillis + 1000, TimeUnit.MILLISECONDS);
            if (batch.getRecordsCount() != 1) throw new IllegalStateException("parked Fetch did not deliver a record");
            entry.delivered = batch.getRecords(0);
            window.delivered(began, finished.get()); // Callback time, independent of receipt-return order.
        } catch (Exception e) {
            status = Status.fromThrowable(Main.cause(e)).getCode(); if (status == Status.Code.OK) status = Status.Code.UNKNOWN;
            fail(status == Status.Code.RESOURCE_EXHAUSTED ? Failure.QUOTA : Failure.OPERATION); window.abort(System.nanoTime());
        } finally {
            window.complete(began, System.nanoTime(), status, status == Status.Code.OK ? 1 : 0, payloadBytes + Math.max(0, keyBytes));
            if (!fetch.isDone()) fetch.cancel(true);
        }
        try { observe(0, null); } catch (Exception cleanup) { fail(Failure.DRAIN); window.abort(System.nanoTime()); }
    }
    private JSONObject verify(GrpcClient rpc) throws Exception {
        Entry[] entries = ledger.snapshot(); var acknowledged = new HashMap<String, Entry>(); long ackCount = 0;
        for (var entry : entries) if (entry.receipt != null) {
            ackCount++; String key = identity(entry.receipt.getPartition(), entry.receipt.getOffset());
            if (acknowledged.put(key, entry) != null) throw new IllegalStateException("duplicate receipt identity");
            if (entry.delivered != null) match(entry, entry.delivered);
        }
        long matched = 0, unacknowledgedPersisted = 0, began = System.nanoTime();
        for (String topic : List.of("warm", "bench")) {
            try (var session = new ConsumerSession(rpc, "verify-" + topic, topic, deadlineMillis)) {
                var view = session.view();
                for (int p = 0; p < view.getAssignmentsCount(); p++) {
                    var partition = view.getAssignments(p); long next = view.getRetainedStartOffsets(p), end = view.getPublishedEndOffsets(p);
                    if (next != 0) throw new IllegalStateException("benchmark retention must be disabled");
                    while (next < end) {
                        if (System.nanoTime() - began > TimeUnit.SECONDS.toNanos(120)) throw new TimeoutException("verification deadline");
                        var batch = session.fetch(partition, next, 128, 65536, 0).response();
                        if (batch.getRecordsCount() == 0 || batch.getNextOffset() <= next) throw new IllegalStateException("verification stalled");
                        for (var record : batch.getRecordsList()) {
                            long sequence = sequence(record.getPayload(), run);
                            if (sequence < 0 || sequence >= entries.length) throw new IllegalStateException("unknown record sequence");
                            Entry expected = entries[(int) sequence];
                            if (!expected.issued || !expected.request.getPayload().equals(record.getPayload()) || expected.request.hasKey() != record.hasKey()
                                    || !expected.request.getKey().equals(record.getKey())) throw new IllegalStateException("verification input mismatch");
                            Entry ack = acknowledged.remove(identity(record.getPartition(), record.getOffset()));
                            if (ack == null) {
                                if (expected.receipt != null) throw new IllegalStateException("acknowledged record duplicated at a different offset");
                                unacknowledgedPersisted++;
                            } else { if (ack != expected) throw new IllegalStateException("record-to-receipt mismatch"); match(ack, record); matched++; }
                        }
                        next = batch.getNextOffset();
                    }
                }
            }
        }
        if (matched != ackCount || !acknowledged.isEmpty()) throw new IllegalStateException("missing acknowledged records");
        for (var commit : commits.entrySet()) {
            String[] parts = commit.getKey().split(":");
            try (var session = new ConsumerSession(rpc, "benchmark-" + parts[0] + "-" + parts[1], parts[0], deadlineMillis)) {
                int p = Integer.parseInt(parts[2]); var view = session.view();
                int index = view.getAssignmentsList().indexOf(TopicPartition.newBuilder().setTopic(parts[0]).setPartition(p).build());
                long actual = view.getCommittedNextOffsets(index);
                if (actual < commit.getValue() || !failed() && actual != commit.getValue()) throw new IllegalStateException("durable progress mismatch");
            }
        }
        return new JSONObject().put("passed", true).put("acknowledgedRecords", ackCount).put("matchedRecords", matched)
                .put("unacknowledgedPersistedRecords", unacknowledgedPersisted).put("committedPartitionsVerified", commits.size());
    }
    private static String identity(TopicPartition p, long offset) { return p.getTopic() + ":" + p.getPartition() + ":" + offset; }
    private void writeLedger(Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) {
            for (var entry : ledger.snapshot()) {
                var value = new JSONObject().put("runId", run.toString()).put("sequence", entry.sequence).put("phase", entry.phase)
                        .put("issued", entry.issued).put("topic", entry.request.getTopic()).put("keyPresent", entry.request.hasKey())
                        .put("keyHex", HexFormat.of().formatHex(entry.request.getKey().toByteArray()))
                        .put("payloadHex", HexFormat.of().formatHex(entry.request.getPayload().toByteArray()))
                        .put("outcome", entry.outcome == null ? "unresolved_or_not_issued" : entry.outcome.name());
                if (entry.receipt != null) value.put("receipt", Main.receipt(entry.receipt));
                writer.write(value.toString()); writer.newLine();
            }
        }
    }
}
