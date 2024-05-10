package tributary.cli;

import tributary.core.TributaryController;

public class MessageHandler {
    private TributaryController controller;

    public MessageHandler() {
        this.controller = new TributaryController();
    }

    public void handleCreateCommand(String[] parts) {
        String subCommand = parts[1].toLowerCase();
        switch (subCommand) {
        case "topic":       
            controller.createTopic(parts[2], parts[3]);
            break;
        case "partition":
            controller.createPartition(parts[2], parts[3]);
            break;
        case "consumer":
            if (parts[2].equals("group")) {
                controller.createConsumerGroup(parts[3], parts[4], parts[5].toLowerCase());
            } else {
                controller.createConsumer(parts[2], parts[3]);
            }
            break;
        case "producer":
            controller.createProducer(parts[2], parts[3].toLowerCase(), parts[4]);
            break;
        case "event":
            controller.createEvent(parts[2], parts[3], parts[4], parts.length > 5 ? parts[5] : null);
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
            controller.deleteConsumer(parts[2]);
            break;
        default:
            System.out.println("Unknown delete command: " + subCommand);
            break;
        }
    }

    public void handleShowCommand(String[] parts) {
        String subCommand = parts[1].toLowerCase();
        switch (subCommand) {
        case "topic":
            controller.showTopic(parts[2]);
            break;
        case "consumer":
            if (parts[2].equals("group")) {
                controller.showGroup(parts[3]);
            }
            break;
        default:
            System.out.println("Unknown show command: " + subCommand);
            break;
        }
    }

    public void handleConsumeCommand(String[] parts) {
        String subcommand = parts[1].toLowerCase();
        switch (subcommand) {
        case "event":
            controller.consumeEvents(parts[2], parts[3], 1);
            break;
        case "events":
            controller.consumeEvents(parts[2], parts[3], Integer.parseInt(parts[4]));
            break;
        default:
            System.out.println("Unknown consume command: " + subcommand);
            break;
        }
    }

    public void handleUpdateCommand(String[] parts) {
        String subcommand = parts[1].toLowerCase();
        switch (subcommand) {
        case "consumer":
            if (parts[2].equals("group") && parts[3].equals("rebalancing")) {
                controller.updateRebalancing(parts[4], parts[5].toLowerCase());
            }
            break;
        default:
            System.out.println("Unknown update command: " + subcommand);
            break;
        }
    }
}
