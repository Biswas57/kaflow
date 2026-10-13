package kaflow.storage;

import java.io.IOException;

public final class StorageException extends IOException {
    public enum Code {
        INVALID_ARGUMENT, LIMIT, CORRUPT, UNSUPPORTED_VERSION, WRONG_IDENTITY,
        OWNERSHIP, WRONG_THREAD, CLOSED, FAILED, BUDGET_TOO_SMALL, IO_STALLED
    }

    private final Code code;

    public StorageException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public StorageException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
