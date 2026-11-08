package kaflow.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class RetentionCrashTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings={"retention-written","retention-forced","retention-replaced","retention-synced","retention-published",
            "retention-delete-before","retention-log-deleted","retention-index-deleted","retention-delete-synced"})
    void processHaltNeverResurrectsExpiredHistoryOrRenumbersOffsets(String point) throws Exception {
        Path data=directory.resolve("data"),output=directory.resolve("child.log");
        String cp=Path.of(RetentionCrashProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())+java.io.File.pathSeparator+Path.of(PartitionLog.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,RetentionCrashProbe.class.getName(),data.toString(),point).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try{assertTrue(child.waitFor(10,TimeUnit.SECONDS),"retention child timeout");assertEquals(78,child.exitValue(),Files.readString(output));}
        finally{if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));}}
        long floor=point.equals("retention-written")||point.equals("retention-forced")?0:9;
        for(int i=0;i<2;i++)try(var store=DurableStore.open(data,RetentionCrashProbe.options(),CLOCK,new Persistence())) {
            assertEquals(floor,store.snapshot(PART).start);assertEquals(10+i,store.snapshot(PART).end);
            var record=store.inspect(PART,9,1,10).get(0);assertEquals(9,record.offset());assertArrayEquals(new byte[]{9},record.payload().toByteArray());
            assertEquals(10+i,store.append(PART,Optional.empty(),EMPTY,false).offset());
        }
    }
}
