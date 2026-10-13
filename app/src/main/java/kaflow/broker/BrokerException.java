package kaflow.broker;

/** Core failure categories; wire-status mapping belongs to the future durable adapter. */
public final class BrokerException extends RuntimeException {
    public enum Code { INVALID_ARGUMENT, NOT_FOUND, ALREADY_EXISTS, RESOURCE_EXHAUSTED, CLOSED }

    private final Code code;

    public BrokerException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
