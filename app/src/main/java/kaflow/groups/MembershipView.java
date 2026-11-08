package kaflow.groups;

import java.util.List;
import kaflow.broker.TopicPartition;

public record MembershipView(SessionIdentity identity, long generation, long leaseMillis,
                             List<TopicPartition> assignments, List<Long> committedOffsets, List<Long> retainedStarts, List<Long> publishedEnds) {
    public MembershipView(SessionIdentity identity, long generation, long leaseMillis, List<TopicPartition> assignments, List<Long> committedOffsets) {
        this(identity, generation, leaseMillis, assignments, committedOffsets, java.util.Collections.nCopies(assignments.size(), 0L), committedOffsets);
    }
    public MembershipView(SessionIdentity identity, long generation, long leaseMillis, List<TopicPartition> assignments) {
        this(identity, generation, leaseMillis, assignments, java.util.Collections.nCopies(assignments.size(), 0L));
    }
    public MembershipView { assignments = List.copyOf(assignments); committedOffsets = List.copyOf(committedOffsets);
        retainedStarts = List.copyOf(retainedStarts); publishedEnds = List.copyOf(publishedEnds);
        if (retainedStarts.size() != assignments.size() || publishedEnds.size() != assignments.size()) throw new IllegalArgumentException("assignment boundaries");
        for (int i = 0; i < assignments.size(); i++) if (retainedStarts.get(i) < 0 || retainedStarts.get(i) > publishedEnds.get(i)) throw new IllegalArgumentException("assignment range");
        if (committedOffsets.size() != assignments.size() || committedOffsets.stream().anyMatch(n -> n < 0)) throw new IllegalArgumentException("assignment progress");
    }
    @Override public String toString() { return "MembershipView[generation=" + generation + ", assignments=" + assignments.size() + "]"; }
}
