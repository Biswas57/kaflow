package kaflow.groups;

import java.util.Objects;
import kaflow.broker.TopicId;

public record JoinCommand(GroupId group, TopicId topic, String memberLabel) {
    public JoinCommand {
        Objects.requireNonNull(group); Objects.requireNonNull(topic);
        GroupId.validate(topic.value(), 255, false);
        GroupId.validate(memberLabel, 128, true);
    }
}
