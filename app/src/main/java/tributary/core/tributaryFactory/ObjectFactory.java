package tributary.core.tributaryFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

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

public class ObjectFactory {
    private TributaryCluster tributaryCluster;
    private Map<String, Class<?>> typeMap;

    public ObjectFactory(TributaryCluster tributaryCluster) {
        this.tributaryCluster = tributaryCluster;
        this.typeMap = new HashMap<>();
        typeMap.put("integer", Integer.class);
        typeMap.put("string", String.class);
    }

    public void createTopic(String[] response) {
        String id = response[2];
        String type = response[3].toLowerCase();
        Class<?> typeClass = typeMap.get(type);
        if (typeClass.equals(Integer.class)) {
            Topic<Integer> topic = new Topic<>(id, Integer.class);
            tributaryCluster.addTopic(topic);
        } else if (typeClass.equals(String.class)) {
            Topic<String> topic = new Topic<>(id, String.class);
            tributaryCluster.addTopic(topic);
        } else {
            System.out.println("Unsupported event type: " + type);
            return;
        }
        System.out.println("Created topic with ID: " + id + " and type: " + typeClass.getSimpleName());
    }

    public void createPartition(String[] response) {
        String topicId = response[2];
        String partitionId = response[3];
        Topic<?> topic = tributaryCluster.getTopic(topicId);
        if (topic == null) {
            System.out.println("Topic with ID " + topicId + " does not exist.");
            return;
        }
        if (topic.getType().equals(Integer.class)) {
            @SuppressWarnings("unchecked")
            Topic<Integer> intTopic = (Topic<Integer>) topic;
            Partition<Integer> newPartition = new Partition<>(topicId, partitionId);
            intTopic.addPartition(newPartition);
        } else if (topic.getType().equals(String.class)) {
            @SuppressWarnings("unchecked")
            Topic<String> stringTopic = (Topic<String>) topic;
            Partition<String> newPartition = new Partition<>(topicId, partitionId);
            stringTopic.addPartition(newPartition);
        } else {
            System.out.println("Unsupported topic type for topic " + topicId);
        }
        System.out.println("Created partition with ID: " + partitionId + " for topic: " + topicId);
    }

    public void createConsumerGroup(String[] response) {
        String id = response[3];
        String topicId = response[4];
        String rebalancing = response[5];
        Topic<?> topic = tributaryCluster.getTopic(topicId);
        if (topic == null) {
            System.out.println("Topic with ID " + topicId + " does not exist.");
            return;
        }
        tributaryCluster.addGroup(new ConsumerGroup<>(id, topic, rebalancing));
        System.out.println("Created consumer group with ID: " + id + " for topic: " + topicId
                + " with rebalancing strategy: " + rebalancing);
    }

    public void createConsumer(String[] response) {
        String groupId = response[2];
        String id = response[3];
        ConsumerGroup<?> consumerGroup = tributaryCluster.getConsumerGroup(groupId);
        if (consumerGroup == null) {
            System.out.println("Consumer group " + groupId + " does not exist.");
            return;
        }
        Topic<?> topic = consumerGroup.getAssignedTopic();
        if (topic.getType().equals(Integer.class)) {
            @SuppressWarnings("unchecked")
            ConsumerGroup<Integer> intGroup = (ConsumerGroup<Integer>) consumerGroup;
            Consumer<Integer> intConsumer = new Consumer<>(groupId, id);
            intGroup.addConsumer(intConsumer);
            intGroup.rebalance();
        } else if (topic.getType().equals(String.class)) {
            @SuppressWarnings("unchecked")
            ConsumerGroup<String> strGroup = (ConsumerGroup<String>) consumerGroup;
            Consumer<String> strConsumer = new Consumer<>(groupId, id);
            strGroup.addConsumer(strConsumer);
            strGroup.rebalance();
        } else {
            System.out.println("Unsupported event type for topic " + consumerGroup.getAssignedTopic());
        }
        System.out.println("Created consumer with ID: " + id + " for group: " + groupId);
    }

    public void createProducer(String[] response) {
        String id = response[2];
        String type = response[3].toLowerCase();
        String allocation = response[4];
        Class<?> typeClass = typeMap.get(type);

        if (typeClass == null) {
            System.out.println("Unsupported event type: " + type);
            return;
        }

        Producer<?> producer;
        if ("manual".equals(allocation)) {
            producer = createProducerOfType(id, typeClass, true);
        } else if ("random".equals(allocation)) {
            producer = createProducerOfType(id, typeClass, false);
        } else {
            System.out.println("Unsupported allocation type: " + allocation);
            return;
        }

        if (producer != null) {
            tributaryCluster.addProducer(producer);
            System.out.println("Created producer with ID: " + id + " that produces " + typeClass.getSimpleName()
                    + " events with allocation type: " + allocation);
        } else {
            System.out.println("Failed to create producer for type: " + type);
        }
    }

    private Producer<?> createProducerOfType(String id, Class<?> typeClass, boolean isManual) {
        if (typeClass.equals(Integer.class)) {
            return isManual ? new ManualProducer<Integer>(id, Integer.class)
                    : new RandomProducer<Integer>(id, Integer.class);
        } else if (typeClass.equals(String.class)) {
            return isManual ? new ManualProducer<String>(id, String.class)
                    : new RandomProducer<String>(id, String.class);
        }
        return null;
    }

    public void createEvent(String[] response) {
        try {
            String producerId = response[2];
            String topicId = response[3];
            String eventId = response[4];
            String partitionId = response.length > 5 ? response[5] : null;

            Topic<?> topic = tributaryCluster.getTopic(topicId);
            if (topic == null) {
                System.out.println("Topic with ID " + topicId + " does not exist.");
                return;
            }

            Producer<?> producer = tributaryCluster.getProducer(producerId);
            if (producer == null) {
                System.out.println("Producer with ID " + producerId + " does not exist.");
                return;
            }

            JSONObject messageJsonObject = new JSONObject(
                    Files.readString(Paths.get("app/src/test/java/tributary/messageConfigs/" + eventId + ".json")));
            if (topic.getType().equals(Integer.class) && producer.getType().equals(Integer.class)) {
                @SuppressWarnings("unchecked")
                Producer<Integer> intProducer = (Producer<Integer>) producer;
                @SuppressWarnings("unchecked")
                Topic<Integer> intTopic = (Topic<Integer>) topic;
                intProducer.produceMessage(intTopic.listPartitions(), partitionId, messageJsonObject);
            } else if (topic.getType().equals(String.class) && producer.getType().equals(String.class)) {
                @SuppressWarnings("unchecked")
                Producer<String> strProducer = (Producer<String>) producer;
                @SuppressWarnings("unchecked")
                Topic<String> strTopic = (Topic<String>) topic;
                strProducer.produceMessage(strTopic.listPartitions(), partitionId, messageJsonObject);
            } else {
                System.out.println("Unsupported event type for topic " + topicId);
            }
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
