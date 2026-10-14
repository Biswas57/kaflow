package kaflow.groups;

import java.util.List;
import kaflow.broker.TopicPartition;

public record MembershipView(SessionIdentity identity, long generation, long leaseMillis,
                             List<TopicPartition> assignments, List<Long> committedOffsets) {
    public MembershipView(SessionIdentity identity, long generation, long leaseMillis, List<TopicPartition> assignments) {
        this(identity, generation, leaseMillis, assignments, java.util.Collections.nCopies(assignments.size(), 0L));
    }
    public MembershipView { assignments = List.copyOf(assignments); committedOffsets = List.copyOf(committedOffsets);
        if (committedOffsets.size() != assignments.size() || committedOffsets.stream().anyMatch(n -> n < 0)) throw new IllegalArgumentException("assignment progress");
    }
    @Override public String toString() { return "MembershipView[generation=" + generation + ", assignments=" + assignments.size() + "]"; }
}
