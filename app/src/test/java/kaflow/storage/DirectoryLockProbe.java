package kaflow.storage;

import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import java.nio.file.Path;
import java.time.Clock;

/** A real separate-JVM contender, with no JUnit/runtime dependencies beyond the storage classes. */
public final class DirectoryLockProbe {
    public static void main(String[] args) throws Exception {
        try (PartitionLog ignored = PartitionLog.open(Path.of(args[0]),
                new TopicPartition(new TopicId("orders"), 2), StorageOptions.defaults(), Clock.systemUTC())) {
            System.out.println("OWNED");
        } catch (StorageException e) {
            if (e.code() != StorageException.Code.OWNERSHIP) throw e;
            System.out.println("CONFLICT");
        }
    }
}
