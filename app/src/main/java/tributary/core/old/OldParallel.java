package tributary.core.old;
// package tributary.core.oldParallel;

// import java.util.ArrayList;
// import java.util.Arrays;
// import java.util.List;
// import java.util.concurrent.ExecutionException;
// import java.util.concurrent.ExecutorService;
// import java.util.concurrent.Executors;
// import java.util.concurrent.Future;
// import java.util.concurrent.TimeUnit;

// import org.json.JSONArray;
// import org.json.JSONObject;
// import org.springframework.http.HttpStatus;
// import org.springframework.http.ResponseEntity;
// import org.springframework.web.bind.annotation.GetMapping;
// import org.springframework.web.bind.annotation.PostMapping;
// import org.springframework.web.bind.annotation.RequestBody;
// import org.springframework.web.bind.annotation.RequestParam;

// import tributary.core.parameterDataStructures.ParallelEventRequest;
// import tributary.core.tributaryObject.Consumer;
// import tributary.core.tributaryObject.Partition;
// import tributary.core.tributaryObject.Topic;
// import tributary.core.tributaryObject.producers.ManualProducer;
// import tributary.core.tributaryObject.producers.Producer;

// public class OldParallel {
// /**
// * Produces a series of events in parallel.
// *
// * @param producers An array of producer IDs.
// * @param topics An array of topic IDs.
// * @param events A JSONArray where each element is an event represented as a
// * JSONObject.
// * @param partitions An array of partition IDs.
// * @throws IllegalArgumentException if input array lengths do not match or if
// * validation fails.
// */
// public void parallelProduce(ParallelEventRequest dto) throws
// IllegalArgumentException, RuntimeException {
// List<String> producers = dto.getProducers();
// List<String> topics = dto.getTopics();
// JSONArray events = dto.getEvents();
// List<String> partitions = dto.getPartitions();

// if (producers.size() != topics.size() || producers.size() != events.length()
// || producers.size() != partitions.size()) {
// throw new IllegalArgumentException("All input arrays must have the same
// length.");
// }

// ExecutorService executorService = Executors.newCachedThreadPool();
// List<Future<?>> futures = new ArrayList<>();

// for (int i = 0; i < producers.size(); i++) {
// final String producerId = producers.get(i);
// final String topicId = topics.get(i);
// final JSONObject eventObj = events.getJSONObject(i);
// final String partitionId = partitions.get(i);

// Producer<?> producer = helper.getProducer(producerId);
// Topic<?> topic = helper.getTopic(topicId);
// if (producer == null || topic == null) {
// throw new IllegalArgumentException("Producer " + producerId + " or topic " +
// topicId + " not found.");
// } else if (!topic.getType().equals(producer.getType())) {
// throw new IllegalArgumentException(
// "Producer " + producerId + " type does not match Topic " + topicId + "
// type.");
// }

// // For manual producers, ensure a valid partition is provided.
// if (producer instanceof ManualProducer) {
// if (partitionId == null || partitionId.isEmpty()) {
// throw new IllegalArgumentException(
// "Manual producer " + producerId + " requires a valid partition id.");
// }
// if (helper.findPartition(partitionId) == null) {
// throw new IllegalArgumentException("Partition " + partitionId + " not
// found.");
// }
// }

// // Submit a task to produce the event.
// Future<?> future = executorService.submit(() -> {
// // Directly use the event object's string representation.
// createEvent(producerId, topicId, eventObj, partitionId);
// });
// futures.add(future);
// }

// executorService.shutdown();
// try {
// executorService.awaitTermination(600, TimeUnit.SECONDS);
// } catch (InterruptedException e) {
// Thread.currentThread().interrupt();
// throw new RuntimeException("Parallel produce interrupted", e);
// }

// for (Future<?> future : futures) {
// try {
// future.get();
// } catch (ExecutionException e) {
// throw new RuntimeException(e.getCause());
// } catch (InterruptedException e) {
// Thread.currentThread().interrupt();
// throw new RuntimeException("Future execution interrupted", e);
// }
// }
// }

// /**
// * Consumes events in parallel from multiple partitions as specified by the
// * input arrays.
// * Returns a JSONObject with an "events" key mapping to a JSONArray containing
// * the consumption results for each consumer (using the consumer's id as the
// * key).
// *
// * @param consumerIds An array of consumer IDs.
// * @param partitionIds An array of partition IDs.
// * @param numEvents An array of numbers specifying how many events to consume
// * for each consumer.
// * @return A JSONObject structured as:
// * { "events": [ { "consumerId1": [ ... ] }, { "consumerId2": [ ... ] },
// * ... ] }
// * @throws IllegalArgumentException if the input arrays do not have the same
// * length or if any required entity is not
// * found.
// */
// public JSONObject parallelConsume(ParallelEventRequest dto) {
// List<String> consumerIds = dto.getConsumers();
// List<String> partitionIds = dto.getPartitions();
// List<Integer> numEvents = dto.getNumEvents();

// if (consumerIds.size() != partitionIds.size() || consumerIds.size() !=
// numEvents.size()) {
// throw new IllegalArgumentException("All input arrays must have the same
// length.");
// }

// ExecutorService executorService =
// Executors.newFixedThreadPool(consumerIds.size());
// List<Future<JSONObject>> futures = new ArrayList<>();

// for (int i = 0; i < consumerIds.size(); i++) {
// final String consumerId = consumerIds.get(i);
// final String partitionId = partitionIds.get(i);
// final int numberOfEvents = numEvents.get(i);

// Partition<?> partition = helper.findPartition(partitionId);
// Consumer<?> consumer = helper.findConsumer(consumerId);
// if (consumer == null || partition == null) {
// throw new IllegalArgumentException(
// "Consumer " + consumerId + " or partition " + partitionId + " not found.");
// }
// Topic<?> topic = partition.getAllocatedTopic();
// if (!helper.verifyConsumer(consumer, topic)) {
// throw new IllegalArgumentException("Consumer group of consumer " + consumerId
// + " does not have permission to consume from topic " + topic.getId() + ".");
// }

// Future<JSONObject> future = executorService.submit(() -> {
// return consumeEvents(consumer.getId(), partition.getId(), numberOfEvents);
// });
// futures.add(future);
// }

// executorService.shutdown();
// try {
// executorService.awaitTermination(60, TimeUnit.SECONDS);
// } catch (InterruptedException e) {
// Thread.currentThread().interrupt();
// throw new RuntimeException("Parallel consume interrupted: " + e.getMessage(),
// e);
// }

// JSONArray eventsArray = new JSONArray();
// for (Future<JSONObject> future : futures) {
// try {
// JSONObject consumerEvents = future.get();
// eventsArray.put(consumerEvents);
// } catch (Exception e) {
// throw new RuntimeException("Error retrieving future result: " +
// e.getMessage(), e);
// }
// }

// JSONObject result = new JSONObject();
// result.put("events", eventsArray);
// return result;
// }

// /**
// * Executes parallel event production using the specified commands.
// *
// * @param commands A whitespace-delimited string of commands for parallel
// * production.
// * @return A ResponseEntity containing a JSON object with a success message.
// */
// @PostMapping("/parallel/produce")
// public ResponseEntity<JSONObject> parallelProduce(@RequestBody String[]
// producerIds,
// @RequestBody String[] topicIds,
// @RequestBody JSONArray events,
// @RequestBody String[] partitionIds) {
// JSONObject response = new JSONObject();
// ParallelEventRequest requestParams = new
// ParallelEventRequest(Arrays.asList(producerIds),
// Arrays.asList(topicIds),
// events, Arrays.asList(partitionIds));
// try {
// controller.parallelProduce(requestParams);
// response.put("message", "Parallel production executed.");
// return new ResponseEntity<>(response, HttpStatus.OK);
// } catch (IllegalArgumentException e) {
// response.put("error", e.getMessage());
// return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
// } catch (RuntimeException e) {
// response.put("error", e.getMessage());
// return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
// }
// }

// /**
// * Executes parallel event consumption using the specified commands.
// *
// * @param commands A whitespace-delimited string of commands for parallel
// * consumption.
// * @return A ResponseEntity containing a JSON object with a success message.
// */
// @PostMapping("/parallel/consume")
// public ResponseEntity<JSONObject> parallelConsume(@RequestBody String[]
// consumerIds,
// @RequestParam String[] partitionIds,
// @RequestParam int[] numEvents) {
// JSONObject response = new JSONObject();

// List<Integer> numEventslist = new ArrayList<>();
// for (int num : numEvents) {
// numEventslist.add(num);
// }
// ParallelEventRequest requestParams = new
// ParallelEventRequest(Arrays.asList(consumerIds),
// Arrays.asList(partitionIds), numEventslist);
// try {
// controller.parallelConsume(requestParams);
// response.put("message", "Parallel production executed.");
// return new ResponseEntity<>(response, HttpStatus.OK);
// } catch (IllegalArgumentException e) {
// response.put("error", e.getMessage());
// return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
// }
// }

// /**
// * Consumes a specified number of events from a given partition using a
// specific
// * consumer.
// *
// * @param consumerId The identifier of the consumer.
// * @param partitionId The identifier of the partition.
// * @param numberOfEvents The number of events to consume.
// * @return A ResponseEntity containing the result of the consumption operation
// * as a JSON object.
// */
// @GetMapping("/consume")
// public ResponseEntity<JSONObject> consumeEvents(@RequestParam String
// consumerId,
// @RequestParam String partitionId,
// @RequestParam int numberOfEvents) {
// JSONObject result = controller.consumeEvents(consumerId, partitionId,
// numberOfEvents);
// if (result == null) {
// JSONObject error = new JSONObject();
// error.put("error", "Failed to consume events.");
// return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
// }
// return new ResponseEntity<>(result, HttpStatus.OK);
// }

// /**
// * Creates an event from a specified producer to a given topic and partition.
// *
// * @param producerId The identifier of the producer.
// * @param topicId The topic to publish the event to.
// * @param eventId The event identifier (could be a filename or unique ID).
// * @param partitionId (Optional) The partition identifier.
// * @return A ResponseEntity containing a JSON object with a success or error
// * message.
// */
// @PostMapping("/events")
// public ResponseEntity<JSONObject> createEvent(@RequestParam String
// producerId,
// @RequestParam String topicId,
// @RequestParam JSONObject event,
// @RequestParam(required = false) String partitionId) {
// JSONObject response = new JSONObject();
// try {
// controller.createEvent(producerId, topicId, event, partitionId);
// response.put("message", "Event '" + event.getString("eventId") + "' created
// for topic '" + topicId + "'.");
// return new ResponseEntity<>(response, HttpStatus.OK);
// } catch (IllegalArgumentException e) {
// response.put("error", e.getMessage());
// return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
// } catch (RuntimeException e) {
// response.put("error", e.getMessage());
// return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
// }
// }
// }
