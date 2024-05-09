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

public class IntegerFactory extends ObjectFactory {
    public IntegerFactory(TributaryCluster tributaryCluster) {
        super();
    }

    @Override
    public void createTopic(String topicId) {
        Topic<Integer> topic = new Topic<>(topicId, Integer.class);
        getCluster().addTopic(topic);
        System.out.println("Created Integer topic with ID: " + topicId);
    }

    @Override
    public void createPartition(String topicId, String partitionId) {
        @SuppressWarnings("unchecked")
        Topic<Integer> topic = (Topic<Integer>) getCluster().getTopic(topicId);
        topic.addPartition(new Partition<Integer>(topicId, partitionId));
        System.out.println("Created partition with ID: " + partitionId + " for topic: " + topicId);
    }

    @Override
    public void createConsumer(String groupId, String consumerId) {
        @SuppressWarnings("unchecked")
        ConsumerGroup<Integer> group = (ConsumerGroup<Integer>) getCluster().getConsumerGroup(groupId);
        Consumer<Integer> consumer = new Consumer<>(groupId, consumerId);
        group.addConsumer(consumer);
        group.rebalance();
        System.out.println("Created consumer with ID: " + consumerId + " for group: " + groupId);
    }

    @Override
    public void createProducer(String producerId, String type, String allocation) {
        Producer<Integer> producer;
        switch (allocation) {
            case "manual":
                producer = new ManualProducer<>(producerId, Integer.class);
                break;
            case "random":
                producer = new RandomProducer<>(producerId, Integer.class);
                break;
            default:
                System.out.println("Unsupported allocation type: " + allocation);
                return;
        }
        getCluster().addProducer(producer);
        System.out.println("Created producer with ID: " + producerId 
                    + " that produces Integer events with allocation type: " + allocation);
    }

    @Override
    public void createEvent(String producerId, String topicId, String eventId, String partitionId) {
        try {
            JSONObject messageJsonObject = new JSONObject(
                    Files.readString(Paths.get("app/src/test/java/tributary/messageConfigs/" + eventId + ".json")));
            @SuppressWarnings("unchecked")
            Producer<Integer> producer = (Producer<Integer>) getCluster().getProducer(producerId);
            @SuppressWarnings("unchecked")
            Topic<Integer> topic = (Topic<Integer>) getCluster().getTopic(topicId);
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
