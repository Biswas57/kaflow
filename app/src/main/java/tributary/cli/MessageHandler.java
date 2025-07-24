package tributary.cli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Random;

import org.json.JSONObject;

import tributary.api.TributaryController;

public class MessageHandler {
    private TributaryController controller;

    public MessageHandler() {
        this.controller = new TributaryController();
    }

    public <T> void handleCreateCommand(String[] parts) {
        String subCommand = parts[1].toLowerCase();
        switch (subCommand) {
            case "topic":
                controller.createTopic(parts[2], parts[3].toLowerCase());
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
                try {
                    Random random = new Random();
                    byte[] key = ByteBuffer.allocate(4).putInt(random.nextInt()).array();
                    JSONObject payload = new JSONObject(
                            Files.readString(Paths.get("messageConfigs/" + parts[4] + ".json")));
                    String messageId = controller.produceMessage(parts[2], parts[3], parts[5], (byte[]) key, payload,
                            LocalDateTime.now(), parts.length > 6 ? parts[6] : null);
                    System.out.println("Produced message " + messageId);
                } catch (IOException e) {
                    System.out.println(e.getMessage());
                }
                break;
            default:
                System.out.println("Unknown create command: " + subCommand);
                break;
        }
    }

    public void handleDeleteCommand(String[] parts) {
        String subCommand = parts[1].toLowerCase();
        switch (subCommand) {
            case "topic":
                controller.deleteTopic(parts[2]);
                break;
            case "consumer":
                if (parts[2].equals("group")) {
                    controller.deleteConsumerGroup(parts[3]);
                } else {
                    controller.deleteConsumer(parts[2], parts[3]);
                }
                break;
            case "producer":
                controller.deleteProducer(parts[2]);
                break;
            case "partition":
                controller.deletePartition(parts[2], parts[3]);
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
                controller.consumeEvent(parts[2], parts[3]);
                break;
            default:
                System.out.println("Unknown consume command: " + subcommand);
                break;
        }
    }

    public void handleUpdateCommand(String[] parts) {
        String subcommand = parts[1].toLowerCase();
        switch (subcommand) {
            case "rebalancing":
                controller.updateRebalancing(parts[2], parts[3].toLowerCase());
                break;
            case "offset":
                controller.updatePartitionOffset(parts[3], parts[4], parts[5],
                        parts.length > 6 ? Integer.parseInt(parts[6]) : -1);
                break;
            default:
                System.out.println("Unknown update command: " + subcommand);
                break;
        }
    }

    public TributaryController getController() {
        return controller;
    }
}
