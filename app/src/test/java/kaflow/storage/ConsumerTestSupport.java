package kaflow.storage;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import kaflow.consumer.*;
import kaflow.groups.*;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.StorageTestSupport.CLOCK;

final class ConsumerTestSupport {
    static final GroupId GROUP = new GroupId("consumer-a");
    static final JoinCommand JOIN = new JoinCommand(GROUP, TOPIC, "worker");
    static DurableBroker start(Path path, AtomicLong time, Persistence disk) throws Exception {
        return start(path, time, disk, ConsumerOptions.defaults());
    }
    static DurableBroker start(Path path, AtomicLong time, Persistence disk, ConsumerOptions limits) throws Exception {
        var broker = DurableBroker.start(path, OPTIONS, GroupMembershipTest.OPTIONS, limits, CLOCK, time::get, disk, e -> {});
        broker.awaitReady(WAIT); return broker;
    }
    static FetchCommand fetch(MembershipView member, long start, int count, long bytes) {
        return new FetchCommand(member.identity(), member.generation(), PART, start, count, bytes);
    }
    static CommitCommand commit(MembershipView member, long offset) { return new CommitCommand(member.identity(), member.generation(), PART, offset); }
}
