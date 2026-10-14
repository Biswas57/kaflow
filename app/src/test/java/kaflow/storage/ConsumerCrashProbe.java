package kaflow.storage;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import kaflow.broker.*;
import kaflow.groups.GroupId;

/** Separate bounded process fixture: no JUnit dependencies and no public administration. */
public final class ConsumerCrashProbe {
    public static void main(String[] args) throws Exception {
        boolean[] armed={false};var disk=new Persistence(){@Override void event(String point){if(armed[0]&&point.equals(args[1]))Runtime.getRuntime().halt(77);}};
        try(var store=DurableStore.open(Path.of(args[0]),DurableOptions.defaults(),Clock.systemUTC(),disk)) {
            var topic=new TopicId("orders");var partition=new TopicPartition(topic,0);var group=new GroupId("consumer-a");
            store.createTopic(topic,1);store.createGroup(group,topic);
            for(int i=0;i<3;i++)store.append(partition,Optional.empty(),ImmutableBytes.copyOf(new byte[]{(byte)i}),false);
            store.commit(group,partition,2,false);armed[0]=true;store.commit(group,partition,args[2].equals("reset")?1:3,args[2].equals("reset"));
            throw new AssertionError("missed failpoint");
        }
    }
}
