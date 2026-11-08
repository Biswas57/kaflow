package kaflow.clients;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import kaflow.v1.*;
import org.json.JSONObject;

/** Explicit examples: no automatic Append retry, hidden processing commits, or storage access. */
public final class Main {
    private static final Set<String> OPTIONS = Set.of("host", "port", "deadline-ms", "topic", "group", "count",
            "key-hex", "partition", "max-records", "max-bytes", "wait-ms", "seconds", "replay-from",
            "accept-retained-start", "format", "process-delay-ms");
    public static void main(String[] args) {
        try {
            if (args.length > 0 && args[0].equals("benchmark")) { Benchmark.run(args); return; }
            if (args.length == 0) throw new IllegalArgumentException("command required: produce, consume, demo");
            var a = new Arguments(args, 1, OPTIONS);
            long deadline = a.number("deadline-ms", 15000, 1, 120000);
            try (var rpc = new GrpcClient(a.text("host", "127.0.0.1"), (int) a.number("port", 9090, 1, 65535), deadline)) {
                switch (args[0]) {
                    case "produce" -> produce(rpc, a);
                    case "consume" -> consume(rpc, a, deadline);
                    case "demo" -> demo(rpc, a, deadline);
                    default -> throw new IllegalArgumentException("unknown command");
                }
            }
        } catch (Exception error) {
            Throwable cause = cause(error);
            if (cause instanceof IllegalArgumentException) System.err.println("Invalid client options or application data: " + cause.getMessage());
            else System.err.println("Client stopped: " + Status.fromThrowable(cause).getCode()
                    + "; Append/Commit may have an unknown outcome; processing may repeat. No automatic Append retry.");
            System.exit(1);
        }
    }
    static Throwable cause(Throwable error) {
        while ((error instanceof ExecutionException || error instanceof CompletionException || error instanceof ConsumerSession.MembershipUnavailable)
                && error.getCause() != null) error = error.getCause();
        return error;
    }
    private static void produce(GrpcClient rpc, Arguments a) {
        int count = (int) a.number("count", 1, 1, 10000);
        for (int i = 0; i < count; i++) {
            byte[] bytes = new JSONObject().put("orderId", "o-" + (42 + i)).put("quantity", 2).toString().getBytes(StandardCharsets.UTF_8);
            var request = AppendRequest.newBuilder().setTopic(a.text("topic", "orders")).setPayload(ByteString.copyFrom(bytes));
            if (a.has("key-hex")) request.setKey(ByteString.copyFrom(HexFormat.of().parseHex(a.text("key-hex", ""))));
            if (a.has("partition")) request.setPartition((int) a.number("partition", 0, 0, 63));
            var receipt = rpc.append(request.build()); // Deliberately no retry after a lost/ambiguous response.
            System.out.println(receipt(receipt));
        }
    }
    static JSONObject receipt(AppendResponse value) {
        return new JSONObject().put("topic", value.getPartition().getTopic()).put("partition", value.getPartition().getPartition())
                .put("offset", value.getOffset()).put("timestampMillis", value.getTimestampMillis());
    }
    private static void consume(GrpcClient rpc, Arguments a, long deadline) throws Exception {
        String format = a.text("format", "json");
        if (!format.equals("json") && !format.equals("bytes")) throw new IllegalArgumentException("format must be json or bytes");
        int target = (int) a.number("count", 1, 1, 100000), maximum = (int) a.number("max-records", 128, 1, 128);
        long bytes = a.number("max-bytes", 65536, 0, 2097152), wait = a.number("wait-ms", 1000, 0, 120000);
        if (wait >= deadline) throw new IllegalArgumentException("Fetch deadline must exceed server wait");
        long budget = TimeUnit.SECONDS.toNanos(a.number("seconds", 30, 1, 3600));
        long delay = a.number("process-delay-ms", 0, 0, 60000);
        boolean replay = a.has("replay-from"), acceptRetained = a.flag("accept-retained-start");
        long replayFrom = a.number("replay-from", 0, 0, Long.MAX_VALUE);
        try (var session = new ConsumerSession(rpc, a.text("group", "example"), a.text("topic", "orders"), deadline)) {
            Thread hook = new Thread(() -> { try { session.close(); } catch (Exception ignored) { System.err.println("consumer cleanup incomplete"); } }, "example-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                long began = System.nanoTime(); int processed = 0;
                MembershipResponse previous = null; var cursors = new HashMap<TopicPartition, Long>();
                while (processed < target && System.nanoTime() - began < budget) {
                    try {
                        var view = session.view();
                        if (previous == null || !previous.getSession().equals(view.getSession()) || previous.getGeneration() != view.getGeneration()) {
                            cursors.clear();
                            for (int i = 0; i < view.getAssignmentsCount(); i++)
                                cursors.put(view.getAssignments(i), replay ? replayFrom : view.getCommittedNextOffsets(i));
                        }
                        previous = view;
                        if (view.getAssignmentsCount() == 0) { Thread.sleep(100); continue; }
                        for (int i = 0; i < view.getAssignmentsCount() && processed < target && System.nanoTime() - began < budget; i++) {
                            var partition = view.getAssignments(i); long offset = cursors.get(partition);
                            if (offset < view.getRetainedStartOffsets(i)) {
                                if (!acceptRetained) throw Status.OUT_OF_RANGE.asRuntimeException();
                                offset = view.getRetainedStartOffsets(i); cursors.put(partition, offset);
                                System.err.println("Explicit retained-start choice: skipped unavailable history for partition " + partition.getPartition());
                            }
                            var batch = session.fetch(partition, offset, Math.min(maximum, target - processed), bytes, wait);
                            boolean accepted = session.process(batch, response -> {
                                for (var record : response.getRecordsList()) {
                                    if (System.nanoTime() - began >= budget) throw new IllegalStateException("processing budget expired; uncommitted effects may repeat");
                                    if (format.equals("json")) {
                                        try {
                                            var json = new JSONObject(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                                    .decode(record.getPayload().asReadOnlyByteBuffer()).toString());
                                            json.getString("orderId"); json.getInt("quantity"); // Application deserialization/processing.
                                        } catch (CharacterCodingException e) { throw new IllegalArgumentException("application JSON is not UTF-8"); }
                                    }
                                    if (delay != 0) try { Thread.sleep(Math.min(delay, Math.max(1, TimeUnit.NANOSECONDS.toMillis(budget - (System.nanoTime() - began))))); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("processing interrupted", e); }
                                    System.out.println(new JSONObject().put("processedPartition", record.getPartition().getPartition()).put("offset", record.getOffset()));
                                }
                            }, !replay);
                            if (!accepted) { previous = null; break; }
                            processed += batch.response().getRecordsCount(); cursors.put(partition, batch.response().getNextOffset());
                        }
                    } catch (Exception error) {
                        var code = Status.fromThrowable(cause(error)).getCode();
                        if (code != Status.Code.FAILED_PRECONDITION && code != Status.Code.RESOURCE_EXHAUSTED && code != Status.Code.UNAVAILABLE
                                && code != Status.Code.DEADLINE_EXCEEDED && code != Status.Code.CANCELLED) throw error;
                        System.err.println("Membership/operation recovery: " + code + "; completed processing may repeat.");
                        previous = null; Thread.sleep(100);
                        try { session.recover(); } catch (ConsumerSession.MembershipUnavailable unavailable) { /* bounded outer deadline */ }
                    }
                }
                if (processed != target) throw new TimeoutException("consumer target not reached within configured duration");
                System.out.println(new JSONObject().put("processed", processed).put("replayWithoutCommit", replay));
            } finally { try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException shuttingDown) { /* hook owns cleanup */ } }
        }
    }
    /** Each invocation adds two records, advances A by two and B by one, and proves replay does not Commit. */
    private static void demo(GrpcClient rpc, Arguments a, long deadline) throws Exception {
        String topic = a.text("topic", "orders"), group = a.text("group", "demo");
        var partition = TopicPartition.newBuilder().setTopic(topic).setPartition(0).build();
        try (var first = new ConsumerSession(rpc, group + "-a", topic, deadline);
             var second = new ConsumerSession(rpc, group + "-b", topic, deadline)) {
            long startA = first.view().getCommittedNextOffsets(0), startB = second.view().getCommittedNextOffsets(0);
            var receipts = new org.json.JSONArray();
            for (int i = 0; i < 2; i++) receipts.put(receipt(rpc.append(AppendRequest.newBuilder().setTopic(topic).setPartition(0)
                    .setKey(ByteString.EMPTY).setPayload(ByteString.copyFrom(new byte[]{0, (byte) 255, (byte) 128, (byte) i})).build())));
            var batchA = first.fetch(partition, startA, 2, 64, 0);
            if (batchA.response().getRecordsCount() != 2 || !first.process(batchA, Main::checkDemoBytes, true)) throw new IllegalStateException("demo A processing failed");
            var batchB = second.fetch(partition, startB, 1, 64, 0);
            if (batchB.response().getRecordsCount() != 1 || !second.process(batchB, Main::checkDemoBytes, true)) throw new IllegalStateException("demo B processing failed");
            first.heartbeat(); second.heartbeat();
            long committedA = first.view().getCommittedNextOffsets(0), committedB = second.view().getCommittedNextOffsets(0);
            var replay = first.fetch(partition, startA, 1, 64, 0); if (!first.process(replay, Main::checkDemoBytes, false)) throw new IllegalStateException("demo replay fenced");
            first.heartbeat();
            if (committedA != startA + 2 || committedB != startB + 1 || first.view().getCommittedNextOffsets(0) != committedA) throw new IllegalStateException("demo progress mismatch");
            var all = first.fetch(partition, 0, 128, 4096, 0).response();
            if (all.getNextOffset() != all.getPublishedEnd()) throw new IllegalStateException("demo exceeds bounded inspection; use a fresh demo store");
            checkDemoBytes(all); var inspected = new org.json.JSONArray();
            for (var record : all.getRecordsList()) inspected.put(receipt(AppendResponse.newBuilder().setPartition(record.getPartition())
                    .setOffset(record.getOffset()).setTimestampMillis(record.getTimestampMillis()).build()));
            System.out.println(new JSONObject().put("receipts", receipts).put("inspectedRecords", inspected).put("resumedA", startA).put("resumedB", startB)
                    .put("committedA", committedA).put("committedB", committedB).put("replayUnchanged", true));
        }
    }
    private static void checkDemoBytes(FetchResponse response) {
        for (var record : response.getRecordsList()) {
            byte[] payload = record.getPayload().toByteArray();
            if (!record.hasKey() || !record.getKey().isEmpty() || payload.length != 4 || payload[0] != 0
                    || payload[1] != -1 || payload[2] != -128 || payload[3] != (byte) (record.getOffset() % 2)) throw new IllegalStateException("demo byte mismatch");
        }
    }
}
