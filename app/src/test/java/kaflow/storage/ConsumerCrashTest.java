package kaflow.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import static kaflow.storage.ConsumerTestSupport.GROUP;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ConsumerCrashTest {
    @TempDir Path directory;
    static Stream<Arguments> cases() { return Stream.of("commit","reset").flatMap(kind->Stream.of("written","forced","replaced","synced").map(point->Arguments.of(kind,"commit-"+point))); }
    @ParameterizedTest @MethodSource("cases")
    void processHaltAtEveryProgressBoundaryRecoversConsistently(String kind,String point) throws Exception {
        Path data=directory.resolve("data"),out=directory.resolve("child.log");
        String cp=Path.of(ConsumerCrashProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())+java.io.File.pathSeparator
                +Path.of(DurableBroker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,
                ConsumerCrashProbe.class.getName(),data.toString(),point,kind).redirectErrorStream(true).redirectOutput(out.toFile()).start();
        try {assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(77,child.exitValue(),Files.readString(out));}
        finally {if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));}}
        long expected=point.endsWith("replaced")||point.endsWith("synced")?(kind.equals("reset")?1:3):2;
        for(int i=0;i<2;i++)try(var store=DurableStore.open(data,DurableOptions.defaults(),CLOCK,new Persistence())) {assertEquals(List.of(expected),store.recovery().progress().get(GROUP));}
    }
}
