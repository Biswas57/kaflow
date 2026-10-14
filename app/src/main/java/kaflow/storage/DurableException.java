package kaflow.storage;

/** Stable application errors; transport adapters must not forward internal causes. */
public final class DurableException extends RuntimeException {
    public enum Code { INVALID_ARGUMENT, OUT_OF_RANGE, DATA_LOSS, NOT_FOUND, ALREADY_EXISTS, RESOURCE_EXHAUSTED, UNAVAILABLE, FENCED, BINDING_CONFLICT, INTERNAL }
    private final Code code;
    DurableException(Code code) { super(code.name()); this.code = code; }
    DurableException(Code code, Throwable cause) { super(code.name(), cause); this.code = code; }
    public Code code() { return code; }
}
