package tributary.cli;

import java.util.Scanner;
import tributary.api.TributaryController;
import tributary.core.tributaryObject.TributaryCluster;

/**
 * @deprecated This CLI interactor is deprecated. Please use the new API-based
 *             CLI interactor.
 */
@Deprecated
public class ControllerCLI implements AutoCloseable {
    private final MessageHandler handler;
    private final Scanner scanner;

    public ControllerCLI(TributaryController controller) {
        this.handler = new MessageHandler(controller);
        this.scanner = new Scanner(System.in);
    }

    public void start() {
        String input;

        while (true) {
            System.out.println("Enter command:");
            input = scanner.nextLine();
            if (input.equals("exit") || input.equals("quit")) {
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

    public MessageHandler getHandler() {
        return handler;
    }

    @Override
    public void close() {
        scanner.close();
    }

    public static void main(String[] args) {
        try (ControllerCLI cli = new ControllerCLI(new TributaryController(new TributaryCluster()))) {
            cli.start();
        }
    }
}
