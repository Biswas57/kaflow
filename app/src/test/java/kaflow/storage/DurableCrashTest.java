package kaflow.storage;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class DurableCrashTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings={"data-written","data-forced","segment-directory-synced","checkpoint-written",
            "checkpoint-forced","checkpoint-replaced","checkpoint-synced","durable-complete"})
    void haltDuringRotatingAppendPreservesRecordedReceipt(String point) throws Exception {
        Path root=directory.resolve("data"), ack=directory.resolve("ack"), output=directory.resolve("child.log");
        String cp=Path.of(DurableCrashProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                +java.io.File.pathSeparator+Path.of(PartitionLog.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,
                DurableCrashProbe.class.getName(),root.toString(),point,ack.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "child timeout"); assertEquals(77,child.exitValue(),Files.readString(output));
        } finally { if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));} }
        ByteBuffer witness=ByteBuffer.wrap(Files.readAllBytes(ack)); long offset=witness.getLong(), timestamp=witness.getLong(); byte payload=witness.get();
        var options=new DurableOptions(new StorageOptions(75,16,16,16,2,128,2097152,8),4,8,16,16,1000,Duration.ofSeconds(30));
        for(int attempt=0;attempt<2;attempt++)try(var store=DurableStore.open(root,options,CLOCK,new Persistence())) {
            var records=store.inspect(PART,0,10,100); assertTrue(records.size()==1||records.size()==2);
            var published=records.get(0); assertEquals(offset,published.offset());assertEquals(timestamp,published.timestampMillis());
            assertArrayEquals(new byte[]{payload},published.payload().toByteArray()); assertTrue(published.key().isEmpty());
            if(point.equals("checkpoint-synced")||point.equals("durable-complete"))assertEquals(2,records.size());
        }
    }
}
