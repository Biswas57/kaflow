package kaflow.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import kaflow.broker.TopicId;
import kaflow.groups.GroupId;

/** Caller-created immutable bounded metadata; its construction is outside broker admission. */
public record ProvisioningPlan(Map<TopicId, Integer> topics) {
    public ProvisioningPlan {
        var copy = new LinkedHashMap<TopicId, Integer>(); int visited = 0;
        for (var entry : topics.entrySet()) {
            if (++visited > 128 || entry.getValue() == null || entry.getValue() < 1 || entry.getValue() > 64)
                throw new IllegalArgumentException("provisioning plan bounds");
            GroupId.validate(entry.getKey().value(), 255, false);
            if (copy.putIfAbsent(entry.getKey(), entry.getValue()) != null) throw new IllegalArgumentException("duplicate provisioning entry");
        }
        topics = java.util.Collections.unmodifiableMap(copy);
    }
}
