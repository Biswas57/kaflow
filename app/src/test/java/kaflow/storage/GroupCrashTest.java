package kaflow.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import kaflow.groups.GroupId;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class GroupCrashTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings={"group-catalog-written","group-catalog-forced","group-catalog-replaced","group-catalog-synced"})
    void processHaltRetainsCompletedDefinitionAtEveryCreationBoundary(String point) throws Exception {
        Path data=directory.resolve("data"),out=directory.resolve("child.log");
        String cp=Path.of(GroupCrashProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())+java.io.File.pathSeparator
                +Path.of(DurableBroker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,
                GroupCrashProbe.class.getName(),data.toString(),point).redirectErrorStream(true).redirectOutput(out.toFile()).start();
        try {assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertEquals(77,process.exitValue(),Files.readString(out));}
        finally {if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));}}
        for(int i=0;i<2;i++)try(var store=DurableStore.open(data,DurableOptions.defaults(),CLOCK,new Persistence())) {
            var groups=store.recovery().groups();assertEquals(new GroupId("acknowledged"),groups.get(0).id());
            assertEquals(point.endsWith("replaced")||point.endsWith("synced")?2:1,groups.size());
        }
    }
}
