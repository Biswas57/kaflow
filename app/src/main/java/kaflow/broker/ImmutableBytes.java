package kaflow.broker;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/** Opaque bytes: mutable arrays never transfer ownership across this boundary. */
public final class ImmutableBytes {
    private final byte[] bytes;

    private ImmutableBytes(byte[] bytes) {
        this.bytes = bytes.clone();
    }

    public static ImmutableBytes copyOf(byte[] bytes) {
        return new ImmutableBytes(Objects.requireNonNull(bytes, "bytes"));
    }

    /** Allocation-free inspection, including before admission. */
    public int length() {
        return bytes.length;
    }

    public byte[] toByteArray() {
        return bytes.clone();
    }

    /** Independent cursor; neither writes nor access to the backing array are possible. */
    public ByteBuffer asReadOnlyBuffer() {
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImmutableBytes value && Arrays.equals(bytes, value.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
