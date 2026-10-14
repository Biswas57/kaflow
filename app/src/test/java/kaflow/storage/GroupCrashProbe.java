package kaflow.storage;

import java.nio.file.Path;
import java.time.Clock;
import kaflow.broker.TopicId;
import kaflow.groups.GroupId;

/** No JUnit dependency in the separate process; halt at the actual persistence seam. */
public final class GroupCrashProbe {
    public static void main(String[] args) throws Exception {
        boolean[] armed={false};
        var disk=new Persistence(){ @Override void event(String point){if(armed[0] && point.equals(args[1]))Runtime.getRuntime().halt(77);} };
        try(var store=DurableStore.open(Path.of(args[0]),DurableOptions.defaults(),Clock.systemUTC(),disk)) {
            var topic=new TopicId("orders");store.createTopic(topic,1);store.createGroup(new GroupId("acknowledged"),topic);
            armed[0]=true;store.createGroup(new GroupId("uncertain"),topic);throw new AssertionError("missed failpoint");
        }
    }
}
