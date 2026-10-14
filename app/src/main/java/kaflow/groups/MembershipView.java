package kaflow.groups;

import java.util.List;
import kaflow.broker.TopicPartition;

public record MembershipView(SessionIdentity identity, long generation, long leaseMillis,
                             List<TopicPartition> assignments) {
    public MembershipView { assignments = List.copyOf(assignments); }
    @Override public String toString() { return "MembershipView[generation=" + generation + ", assignments=" + assignments.size() + "]"; }
}
