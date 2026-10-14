package kaflow.consumer;

import java.util.Objects;
import kaflow.groups.SessionIdentity;
import kaflow.broker.TopicPartition;

public record FetchCommand(SessionIdentity session, long generation, TopicPartition partition,
                           long startOffset, int maxRecords, long maxBytes) {
    public FetchCommand { Objects.requireNonNull(session); Objects.requireNonNull(partition); }
    @Override public String toString() { return "FetchCommand[partition=" + partition + ", startOffset=" + startOffset + "]"; }
}
