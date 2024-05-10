package tributary.cli;

import java.util.Scanner;

public class TributaryCLI {
    private MessageHandler handler;
    private Scanner scanner;

    public TributaryCLI() {
        this.handler = new MessageHandler();
        this.scanner = new Scanner(System.in);
    }

    public void start() {
        String input;

        while (true) {
            System.out.println("Enter command:");
            input = scanner.nextLine();
            if (input.equals("exit")) {
                System.out.println("Exiting Tributary CLI.\n");
                break;
            }
            processCommand(input);
        }
    }

    public void processCommand(String input) {
        String[] parts = input.split(" ");
        String command = parts[0];
        switch (command) {
        case "create":
            handler.handleCreateCommand(parts);
            break;
        case "delete":
            handler.handleDeleteCommand(parts);
            break;
        case "show":
            handler.handleShowCommand(parts);
            break;
        case "consume":
            handler.handleConsumeCommand(parts);
            break;
        case "update":
            handler.handleUpdateCommand(parts);
            break;
        default:
            System.out.println("Invalid command.\n");
            break;
        }

    }

    public static void main(String[] args) {
        new TributaryCLI().start();
    }
}
