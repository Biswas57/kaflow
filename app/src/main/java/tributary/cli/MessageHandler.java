// package tributary.cli;

// import java.io.IOException;
// import java.util.Arrays;

// import tributary.api.TributaryController;

// @Deprecated
// @SuppressWarnings("deprecation")
// public class MessageHandler {
// private TributaryController controller;

// public MessageHandler() {
// this.controller = new TributaryController();
// }

// public void handleCreateCommand(String[] parts) {
// String subCommand = parts[1].toLowerCase();
// switch (subCommand) {
// case "topic":
// controller.createTopic(parts[2], parts[3].toLowerCase());
// break;
// case "partition":
// controller.createPartition(parts[2], parts[3]);
// break;
// case "consumer":
// if (parts[2].equals("group")) {
// controller.createConsumerGroup(parts[3], parts[4], parts[5].toLowerCase());
// } else {
// controller.createConsumer(parts[2], parts[3]);
// }
// break;
// case "producer":
// controller.createProducer(parts[2], parts[3].toLowerCase(), parts[4]);
// break;
// case "event":
// try {
// controller.createEvent(parts[2], parts[3], parts[4], parts.length > 5 ?
// parts[5] : null);
// } catch (IOException e) {
// System.out.println(e.getMessage());
// }
// break;
// default:
// System.out.println("Unknown create command: " + subCommand);
// break;
// }
// }

// public void handleDeleteCommand(String[] parts) {
// String subCommand = parts[1].toLowerCase();
// switch (subCommand) {
// case "consumer":
// controller.deleteConsumer(parts[2]);
// break;
// default:
// System.out.println("Unknown delete command: " + subCommand);
// break;
// }
// }

// public void handleShowCommand(String[] parts) {
// String subCommand = parts[1].toLowerCase();
// switch (subCommand) {
// case "topic":
// controller.showTopic(parts[2]);
// break;
// case "consumer":
// if (parts[2].equals("group")) {
// controller.showGroup(parts[3]);
// }
// break;
// default:
// System.out.println("Unknown show command: " + subCommand);
// break;
// }
// }

// public void handleConsumeCommand(String[] parts) {
// String subcommand = parts[1].toLowerCase();
// switch (subcommand) {
// case "event":
// controller.consumeEvents(parts[2], parts[3], 1);
// break;
// case "events":
// controller.consumeEvents(parts[2], parts[3], Integer.parseInt(parts[4]));
// break;
// default:
// System.out.println("Unknown consume command: " + subcommand);
// break;
// }
// }

// public void handleUpdateCommand(String[] parts) {
// String subcommand = parts[1].toLowerCase();
// switch (subcommand) {
// case "rebalancing":
// controller.updateRebalancing(parts[2], parts[3].toLowerCase());
// break;
// case "playback":
// if (parts[2].equals("offset")) {
// controller.updatePartitionOffset(parts[3], parts[4],
// parts.length > 5 ? Integer.parseInt(parts[5]) : -1);
// }
// break;
// case "admin":
// if (parts[2].equals("producer")) {
// controller.updateProducerAdmin(parts[3], parts.length > 4 ? parts[4] : null,
// parts.length > 4 ? parts[5] : null);
// } else if (parts[2].equals("consumer")) {
// controller.updateConsumerGroupAdmin(parts[3], parts.length > 4 ? parts[4] :
// null,
// parts.length > 4 ? parts[5] : null);
// }
// break;
// default:
// System.out.println("Unknown update command: " + subcommand);
// break;
// }
// }

// public void handleParallelCommand(String[] parts) {
// String subcommand = parts[1].toLowerCase();
// switch (subcommand) {
// case "produce":
// controller.parallelProduce(Arrays.copyOfRange(parts, 2, parts.length));
// break;
// case "consume":
// controller.parallelConsume(Arrays.copyOfRange(parts, 2, parts.length));
// break;
// default:
// System.out.println("Unknown parallel command: " + subcommand);
// break;
// }
// }

// public TributaryController getController() {
// return controller;
// }
// }
