package tributary.core.typeHandlerFactory;

import java.nio.charset.StandardCharsets;

public class ByteHandler implements TypeHandler<byte[]> {

    @Override
    public byte[] handle(Object value) {
        if (value instanceof String) {
            // Convert the string to a UTF-8 encoded byte array
            return ((String) value).getBytes(StandardCharsets.UTF_8);
        } else if (value instanceof byte[]) {
            // Directly return byte array if already in byte[] format
            return (byte[]) value;
        } else {
            throw new IllegalArgumentException("Payload value must be a String or byte[]: " + value);
        }
    }

    @Override
    public String valueToString(Object value) {
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
        return value.toString();
    }

    @Override
    public byte[] stringToValue(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}