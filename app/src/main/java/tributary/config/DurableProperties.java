package tributary.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kaflow.broker.TopicId;
import kaflow.groups.GroupId;
import kaflow.groups.GroupOptions;
import kaflow.consumer.ConsumerOptions;
import kaflow.consumer.WaitOptions;
import kaflow.storage.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Startup-only configuration. Constructor bounds remain the broker's authority. */
@ConstructorBinding
@ConfigurationProperties(prefix = "kaflow.durable", ignoreUnknownFields = false)
public record DurableProperties(@DefaultValue("false") boolean enabled, String directory,
        @DefaultValue("16777216") long segmentBytes, @DefaultValue("1024") int maxSegments,
        @DefaultValue("0") long retentionBytes, @DefaultValue("8589934592") long maxStorageBytes,
        @DefaultValue("1073741824") long maxPartitionBytes,
        @DefaultValue("10s") Duration shutdownTimeout,
        @DefaultValue Storage storage, @DefaultValue Broker broker, @DefaultValue Groups groups,
        @DefaultValue Consumer consumer, @org.springframework.boot.context.properties.bind.Name("wait") @DefaultValue Wait waitLimits, List<Topic> topics) {

    public DurableProperties {
        if (shutdownTimeout == null || shutdownTimeout.isNegative() || shutdownTimeout.isZero()
                || shutdownTimeout.compareTo(Duration.ofMinutes(5)) > 0) throw new IllegalArgumentException("shutdown timeout must be in (0,5m]");
        topics = topics == null ? List.of() : List.copyOf(topics);
    }
    public record Storage(@DefaultValue("16384") int maxKeyBytes, @DefaultValue("1048576") int maxPayloadBytes,
            @DefaultValue("128") int indexInterval, @DefaultValue("128") int maxReadRecords,
            @DefaultValue("2097152") long maxReadBytes, @DefaultValue("8") int zeroProgressLimit) { }
    public record Broker(@DefaultValue("32") int maxTopics, @DefaultValue("16") int maxPartitionsPerTopic,
            @DefaultValue("64") int maxPartitions, @DefaultValue("128") int maxCalls,
            @DefaultValue("16777216") long maxBytes, @DefaultValue("30s") Duration watchdog) { }
    public record Groups(@DefaultValue("128") int maxGroups, @DefaultValue("32") int maxMembersPerGroup,
            @DefaultValue("256") int maxMembers, @DefaultValue("32") int maxPendingJoins,
            @DefaultValue("128") int maxControls, @DefaultValue("2097152") long maxControlBytes,
            @DefaultValue("131072") int maxCatalogBytes, @DefaultValue("30s") Duration lease) { }
    public record Consumer(@DefaultValue("2") int readWorkers, @DefaultValue("16") int maxReads,
            @DefaultValue("67108864") long maxReadCharge, @DefaultValue("64") int maxProgressCalls,
            @DefaultValue("1048576") long maxProgressCharge) { }
    public record Wait(@DefaultValue("256") int maxRequests, @DefaultValue("16") int maxPerSession,
            @DefaultValue("64") int maxPerPartition, @DefaultValue("524288") long maxMetadataBytes,
            @DefaultValue("30000") long maxWaitMillis, @DefaultValue("8") int batch) { }
    public record Topic(String name, int partitions) { }

    public DurableOptions durableOptions() {
        return new DurableOptions(new StorageOptions(segmentBytes, maxSegments, storage.maxKeyBytes,
                storage.maxPayloadBytes, storage.indexInterval, storage.maxReadRecords, storage.maxReadBytes,
                storage.zeroProgressLimit), broker.maxTopics, broker.maxPartitionsPerTopic, broker.maxPartitions,
                broker.maxCalls, broker.maxBytes, broker.watchdog);
    }
    public GroupOptions groupOptions() {
        return new GroupOptions(groups.maxGroups, groups.maxMembersPerGroup, groups.maxMembers,
                groups.maxPendingJoins, groups.maxControls, groups.maxControlBytes, groups.maxCatalogBytes, groups.lease);
    }
    public ConsumerOptions consumerOptions() {
        return new ConsumerOptions(consumer.readWorkers, consumer.maxReads, consumer.maxReadCharge,
                consumer.maxProgressCalls, consumer.maxProgressCharge);
    }
    public WaitOptions waitOptions() {
        return new WaitOptions(waitLimits.maxRequests, waitLimits.maxPerSession, waitLimits.maxPerPartition,
                waitLimits.maxMetadataBytes, waitLimits.maxWaitMillis, waitLimits.batch);
    }
    public LifecycleOptions lifecycleOptions() { return new LifecycleOptions(retentionBytes, maxStorageBytes, maxPartitionBytes); }

    /** Local syntax only; recovered-state capacity and conflicts are checked by coordination. */
    public Map<TopicId, Integer> provisionedTopics(DurableOptions options) {
        if (directory == null || directory.isBlank()) throw new IllegalArgumentException("durable directory is required");
        if (topics.size() > options.maxTopics()) throw new IllegalArgumentException("too many provisioning topics");
        var result = new LinkedHashMap<TopicId, Integer>(); int partitions = 0;
        for (var topic : topics) {
            GroupId.validate(topic.name, 255, false); // Strict UTF-8; no replacement of malformed identifiers.
            if (topic.partitions < 1 || topic.partitions > options.maxPartitionsPerTopic()) throw new IllegalArgumentException("provisioning partition count");
            if (result.putIfAbsent(new TopicId(topic.name), topic.partitions) != null) throw new IllegalArgumentException("duplicate provisioning topic");
            partitions = Math.addExact(partitions, topic.partitions);
        }
        if (partitions > options.maxPartitions()) throw new IllegalArgumentException("provisioning partition capacity");
        return java.util.Collections.unmodifiableMap(result);
    }
}
