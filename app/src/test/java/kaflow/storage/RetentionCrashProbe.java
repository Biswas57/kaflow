package kaflow.storage;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import kaflow.broker.*;
import kaflow.groups.GroupOptions;

/** Separate process halts without closing resources, after durable records and at retention boundaries. */
public final class RetentionCrashProbe {
    static DurableOptions options() { return new DurableOptions(new StorageOptions(160,16,16,16,2,128,2097152,8),4,8,16,16,1000,Duration.ofSeconds(30)); }
    public static void main(String[] args) throws Exception {
        boolean[] armed={false};
        Persistence disk=new Persistence(){@Override void event(String point){if(armed[0]&&point.equals(args[1]))Runtime.getRuntime().halt(78);}};
        var topic=new TopicId("orders");var part=new TopicPartition(topic,0);
        try(var store=DurableStore.open(Path.of(args[0]),options(),GroupOptions.defaults(),new LifecycleOptions(200,1L<<24,1L<<20),Clock.systemUTC(),disk)) {
            store.createTopic(topic,1);for(int i=0;i<10;i++)store.append(part,Optional.empty(),ImmutableBytes.copyOf(new byte[]{(byte)i}),false);
            armed[0]=true;store.maintain(part);throw new AssertionError("failpoint not reached");
        }
    }
}
