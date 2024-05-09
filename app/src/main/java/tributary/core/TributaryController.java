package tributary.core;

import java.util.List;

import tributary.api.Consumer;
import tributary.api.ConsumerGroup;
import tributary.api.Message;
import tributary.api.Partition;
import tributary.api.Topic;
import tributary.api.TributaryCluster;
import tributary.core.tributaryFactory.ObjectFactory;

public class TributaryController {
    private TributaryCluster tributaryCluster;
    private ObjectFactory objectFactory;

    public TributaryController() {
        this.tributaryCluster = new TributaryCluster();
        this.objectFactory = new ObjectFactory(tributaryCluster);
    }

    public void handleCreateCommand(String[] parts) {
        String subCommand = parts[1].toLowerCase();
        switch (subCommand) {
        case "topic":
            objectFactory.createTopic(parts);
            break;
        case "partition":
            objectFactory.createPartition(parts);
            break;
        case "consumer":
            if (parts[2].equals("group")) {
                objectFactory.createConsumerGroup(parts);
            } else {
                objectFactory.createConsumer(parts);
            }
            break;
        case "producer":
            objectFactory.createProducer(parts);
            break;
        case "event":
            objectFactory.createEvent(parts);
            break;
        default:
            System.out.println("Unknown create command: " + subCommand);
            break;
        }
    }

    public void handleDeleteCommand(String[] parts) {
        String subCommand = parts[1].toLowerCase();
        switch (subCommand) {
        case "consumer":
            String consumerId = parts[2];
            deleteConsumer(consumerId);
            break;
        default:
            System.out.println("Unknown delete command: " + subCommand);
            break;
        }
    }

    public void deleteConsumer(String consumerId) {
        tributaryCluster.deleteConsumer(consumerId);
    }

    public void handleShowCommand(String[] parts) {
        String subCommand = parts[1].toLowerCase();
        switch (subCommand) {
        case "topic":
            showTopic(parts[2]);
            break;
        case "consumer":
            if (parts[2].equals("group")) {
                showGroup(parts[3]);
            }
            break;
        default:
            System.out.println("Unknown show command: " + subCommand);
            break;
        }
    }

    public void showTopic(String topicId) {
        Topic<?> topic = tributaryCluster.getTopic(topicId);
        if (topic != null) {
            topic.showTopic();
        } else {
            System.out.println("Topic not found: " + topicId);
        }
    }

    public void showGroup(String groupId) {
        ConsumerGroup<?> group = tributaryCluster.getConsumerGroup(groupId);
        if (group != null) {
            group.showGroup();
        } else {
            System.out.println("Group not found: " + groupId);
        }
    }

    public void handleConsumeCommand(String[] parts) {
        String subcommand = parts[1].toLowerCase();
        switch (subcommand) {
        case "event":
            consumeEvents(parts, 1);
            break;
        case "events":
            consumeEvents(parts, Integer.parseInt(parts[4]));
            break;
        default:
            System.out.println("Unknown consume command: " + subcommand);
            break;
        }
    }

    private Consumer<?> findConsumer(String consumerId) {
        Consumer<?> specifiedConsumer = null;
        for (ConsumerGroup<?> group : tributaryCluster.listConsumerGroups()) {
            for (Consumer<?> consumer : group.listConsumers()) {
                if (consumer.getId().equals(consumerId)) {
                    specifiedConsumer = consumer;
                }
            }
        }
        return specifiedConsumer;
    }

    private Partition<?> findPartition(String partitionId) {
        Partition<?> specifiedPartition = null;
        for (Topic<?> topic : tributaryCluster.listTopics()) {
            for (Partition<?> partition : topic.listPartitions()) {
                if (partition.getId().equals(partitionId)) {
                    specifiedPartition = partition;
                    break;
                }
            }
            if (specifiedPartition != null)
                break;
        }

        return specifiedPartition;
    }

    /*
     * Consume events from a partition. The number of events to consume is specified
     * by numberOfEvents.
     * @precondition: The consumer must be assigned to the partition.
     */
    public void consumeEvents(String[] parts, int numberOfEvents) {
        String consumerId = parts[2];
        String partitionId = parts[3];

        Consumer<?> consumer = findConsumer(consumerId);
        Partition<?> partition = findPartition(partitionId);
        String topicId = partition.getTopic();
        Topic<?> topic = tributaryCluster.getTopic(topicId);

        if (!consumer.listAssignedPartitions().contains(partition)) {
            System.out.println("Partition " + partitionId + " is not assigned to consumer " + consumerId);
            return;
        }

        if (topic.getType().equals(Integer.class)) {
            @SuppressWarnings("unchecked")
            Consumer<Integer> intConsumer = (Consumer<Integer>) consumer;
            @SuppressWarnings("unchecked")
            Partition<Integer> intPartition = (Partition<Integer>) partition;
            consumeHelper(Integer.class, intConsumer, intPartition, numberOfEvents);
        } else if (topic.getType().equals(String.class)) {
            @SuppressWarnings("unchecked")
            Consumer<String> strConsumer = (Consumer<String>) consumer;
            @SuppressWarnings("unchecked")
            Partition<String> strPartition = (Partition<String>) partition;
            consumeHelper(String.class, strConsumer, strPartition, numberOfEvents);
        }
    }

    public <T> void consumeHelper(Class<T> type, Consumer<T> consumer, Partition<T> partition, int numberOfEvents) {
        List<Message<T>> messages = partition.listMessages();
        int currentOffset = consumer.getOffset(partition);
        int count = 0;

        for (int i = currentOffset + 1; i < messages.size() && count < numberOfEvents; i++, count++) {
            consumer.consume(messages.get(i), partition);
        }

        if (count < numberOfEvents) {
            System.out.println("Not enough messages to consume. Consumed: " + count);
        } else {
            System.out.println("Consumed " + count + " messages.");
        }
    }

    public void handleUpdateCommand(String[] parts) {
        String subcommand = parts[1].toLowerCase();
        switch (subcommand) {
        case "consumer":
            if (parts[2].equals("group") && parts[3].equals("rebalancing")) {
                updateRebalancing(parts);
            }
            break;
        default:
            System.out.println("Unknown update command: " + subcommand);
            break;
        }
    }

    public void updateRebalancing(String[] parts) {
        String groupId = parts[4];
        String rebalancing = parts[5].toLowerCase();
        ConsumerGroup<?> group = tributaryCluster.getConsumerGroup(groupId);
        if (group == null) {
            System.out.println("Consumer group not found: " + groupId);
            return;
        }
        group.setRebalancingMethod(rebalancing);
        group.rebalance();
        System.out.println("Updated rebalancing strategy for group: " + groupId + " to " + rebalancing);
    }

}
