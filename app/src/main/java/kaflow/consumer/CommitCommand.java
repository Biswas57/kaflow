package kaflow.consumer;

import java.util.Objects;
import kaflow.groups.SessionIdentity;
import kaflow.broker.TopicPartition;

public record CommitCommand(SessionIdentity session, long generation, TopicPartition partition, long nextOffset) {
    public CommitCommand { Objects.requireNonNull(session); Objects.requireNonNull(partition); }
    @Override public String toString() { return "CommitCommand[partition=" + partition + ", nextOffset=" + nextOffset + "]"; }
}
