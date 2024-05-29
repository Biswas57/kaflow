package tributary.core.tributaryFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.json.JSONException;
import org.json.JSONObject;

import tributary.api.Consumer;
import tributary.api.ConsumerGroup;
import tributary.api.Partition;
import tributary.api.Topic;
import tributary.api.TributaryCluster;
import tributary.api.producers.ManualProducer;
import tributary.api.producers.Producer;
import tributary.api.producers.RandomProducer;

public class StringFactory extends ObjectFactory {
    public StringFactory() {
        setCluster(TributaryCluster.getInstance());
    }

    @Override
    public void createTopic(String topicId) {
        Topic<String> topic = new Topic<>(topicId, String.class);
        getCluster().addTopic(topic);
        System.out.println("Created String topic with ID: " + topicId + "\n");
    }

    @Override
    public void createPartition(String topicId, String partitionId) {
        @SuppressWarnings("unchecked")
        Topic<String> topic = (Topic<String>) getCluster().getTopic(topicId);
        topic.addPartition(new Partition<String>(topicId, partitionId));
        System.out.println("Created partition with ID: " + partitionId + " for topic: " + topicId + "\n");
    }

    @Override
    public void createConsumer(String groupId, String consumerId) {
        @SuppressWarnings("unchecked")
        ConsumerGroup<String> group = (ConsumerGroup<String>) getCluster().getConsumerGroup(groupId);
        Consumer<String> consumer = new Consumer<>(groupId, consumerId);
        group.addConsumer(consumer);
        group.rebalance();
        System.out.println("Created consumer with ID: " + consumerId + " for group: " + groupId + "\n");
    }

    @Override
    public void createProducer(String producerId, String type, String allocation) {
        Producer<String> producer;
        switch (allocation) {
            case "manual":
                producer = new ManualProducer<>(producerId, String.class);
                break;
            case "random":
                producer = new RandomProducer<>(producerId, String.class);
                break;
            default:
                System.out.println("Unsupported allocation type: " + allocation + "\n");
                return;
        }
        getCluster().addProducer(producer);
        System.out.println("Created producer with ID: " + producerId
                    + " that produces String events with " + allocation + " allocation\n");
    }

    @Override
    public void createEvent(String producerId, String topicId, String eventId, String partitionId) {
        try {
            JSONObject messageJsonObject = new JSONObject(
                    Files.readString(Paths.get("app/src/test/java/tributary/messageConfigs/" + eventId + ".json")));
            @SuppressWarnings("unchecked")
            Producer<String> producer = (Producer<String>) getCluster().getProducer(producerId);
            @SuppressWarnings("unchecked")
            Topic<String> topic = (Topic<String>) getCluster().getTopic(topicId);
            producer.produceMessage(topic.listPartitions(), partitionId, messageJsonObject);
        } catch (IOException e) {
            System.out.println("Error reading the file: " + e.getMessage());
            e.printStackTrace();
        } catch (JSONException e) {
            System.out.println("Error parsing JSON content: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.out.println("Error processing event: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
