package kaflow.storage;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ProvisioningTest {
    @TempDir Path directory;
    @Test void equalEnsureSurvivesRestartAndStrictCreateRemainsStrict() throws Exception {
        for (int i = 0; i < 2; i++) try (var broker = DurableBroker.open(directory, OPTIONS, CLOCK)) {
            await(broker.preflightTopics(new ProvisioningPlan(Map.of(TOPIC, 2))));
            await(broker.ensureTopic(TOPIC, 2)); await(broker.ensureTopic(TOPIC, 2));
            assertEquals(2, broker.diagnostics().published().size());
            failure(DurableException.Code.ALREADY_EXISTS, broker.createTopic(TOPIC, 2));
            failure(DurableException.Code.ALREADY_EXISTS, broker.ensureTopic(TOPIC, 1));
        }
    }
    @Test void conflictingLateDefinitionDoesNotCreateEarlierMissingTopic() throws Exception {
        var missing = new TopicId("first-missing");
        try (var broker = DurableBroker.open(directory, OPTIONS, CLOCK)) {
            await(broker.createTopic(TOPIC, 1));
            var requested = new LinkedHashMap<TopicId, Integer>(); requested.put(missing, 1); requested.put(TOPIC, 2);
            failure(DurableException.Code.ALREADY_EXISTS, broker.preflightTopics(new ProvisioningPlan(requested)));
            assertFalse(broker.diagnostics().published().containsKey(new TopicPartition(missing, 0)));
        }
        try (var broker = DurableBroker.open(directory, OPTIONS, CLOCK)) {
            assertEquals(1, broker.diagnostics().published().size());
            assertFalse(broker.diagnostics().published().containsKey(new TopicPartition(missing, 0)));
        }
    }
    @Test void preflightCountsOnlyMissingDefinitionsAndRejectsAggregateCapacity() throws Exception {
        var options = new DurableOptions(OPTIONS.storage(), 2, 2, 2, OPTIONS.maxCalls(), OPTIONS.maxBytes(), OPTIONS.watchdog());
        try (var broker = DurableBroker.open(directory, options, CLOCK)) {
            await(broker.createTopic(TOPIC, 1));
            var other = new TopicId("other");
            await(broker.preflightTopics(new ProvisioningPlan(Map.of(TOPIC, 1, other, 1))));
            failure(DurableException.Code.RESOURCE_EXHAUSTED, broker.preflightTopics(new ProvisioningPlan(Map.of(TOPIC, 1, other, 2))));
            assertEquals(1, broker.diagnostics().published().size());
            await(broker.ensureTopic(other, 1));
            await(broker.preflightTopics(new ProvisioningPlan(Map.of(TOPIC, 1, other, 1))));
        }
    }
    @Test void administrationRejectsAfterClosure() throws Exception {
        var broker = DurableBroker.open(directory, OPTIONS, CLOCK); broker.close();
        failure(DurableException.Code.UNAVAILABLE, broker.preflightTopics(new ProvisioningPlan(Map.of(TOPIC, 1))));
        failure(DurableException.Code.UNAVAILABLE, broker.ensureTopic(TOPIC, 1));
    }
    @Test void zeroAppendByteBudgetStillAllowsBoundedMetadataProvisioning() throws Exception {
        var zero = new DurableOptions(OPTIONS.storage(), 2, 2, 2, 2, 0, OPTIONS.watchdog());
        try (var broker = DurableBroker.open(directory, zero, CLOCK)) {
            var input = new LinkedHashMap<TopicId, Integer>(); input.put(TOPIC, 1);
            var plan = new ProvisioningPlan(input); input.clear();
            assertEquals(1, plan.topics().size());
            assertThrows(UnsupportedOperationException.class, () -> plan.topics().clear());
            await(broker.preflightTopics(plan)); await(broker.ensureTopic(TOPIC, 1));
            assertEquals(0, await(broker.append(command())).offset());
        }
    }
    @Test void ioFailureLeavesDurablePrefixAndRestartCompletesIdempotently() throws Exception {
        var other = new TopicId("later"); var requested = new LinkedHashMap<TopicId, Integer>();
        requested.put(TOPIC, 1); requested.put(other, 1);
        var disk = new Events();
        try (var broker = start(directory, OPTIONS, new java.util.concurrent.atomic.AtomicLong(), disk, e -> {})) {
            await(broker.preflightTopics(new ProvisioningPlan(requested)));
            await(broker.ensureTopic(TOPIC, 1));
            disk.fail = "topic-directories-synced";
            failure(DurableException.Code.INTERNAL, broker.ensureTopic(other, 1));
            broker.awaitIdle(WAIT);
            assertEquals(DurableBroker.State.DEGRADED, broker.diagnostics().state());
        }
        try (var broker = DurableBroker.open(directory, OPTIONS, CLOCK)) {
            assertEquals(1, broker.diagnostics().published().size());
            await(broker.preflightTopics(new ProvisioningPlan(requested)));
            await(broker.ensureTopic(TOPIC, 1)); await(broker.ensureTopic(other, 1));
            assertEquals(2, broker.diagnostics().published().size());
            assertEquals(0, await(broker.append(command())).offset());
        }
    }
    @Test void lateFrameOrAggregateCatalogLimitRejectsBeforeAnyTopicIsCreated() throws Exception {
        var groups = new kaflow.groups.GroupOptions(2, 2, 2, 2, 2, 4096, 64, java.time.Duration.ofSeconds(30));
        try (var broker = DurableBroker.open(directory, OPTIONS, groups, CLOCK)) {
            var frame = new LinkedHashMap<TopicId, Integer>(); frame.put(TOPIC, 1); frame.put(new TopicId("x".repeat(128)), 1);
            failure(DurableException.Code.INVALID_ARGUMENT, broker.preflightTopics(new ProvisioningPlan(frame)));
            failure(DurableException.Code.RESOURCE_EXHAUSTED,
                    broker.preflightTopics(new ProvisioningPlan(Map.of(TOPIC, 1, new TopicId("other"), 1))));
            assertTrue(broker.diagnostics().published().isEmpty()); assertTrue(broker.diagnostics().ready());
        }
    }
}
