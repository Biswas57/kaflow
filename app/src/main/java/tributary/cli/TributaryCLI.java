package tributary.cli;

import tributary.core.TributaryController;

import java.util.Scanner;

public class TributaryCLI {
    private TributaryController controller;
    private Scanner scanner;

    public TributaryCLI() {
        this.controller = new TributaryController();
        this.scanner = new Scanner(System.in);
    }

    public void start() {
        String input;

        while (true) {
            System.out.println("Enter command:");
            input = scanner.nextLine();
            if (input.equals("exit")) {
                System.out.println("Exiting Tributary CLI.");
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
            controller.handleCreateCommand(parts);
            break;
        case "delete":
            controller.handleDeleteCommand(parts);
            break;
        case "show":
            controller.handleShowCommand(parts);
            break;
        case "consume":
            controller.handleConsumeCommand(parts);
            break;
        case "update":
            controller.handleUpdateCommand(parts);
            break;
        default:
            System.out.println("Invalid command.");
            break;
        }

    }

    public static void main(String[] args) {
        new TributaryCLI().start();
    }
}
