package tributary.core.util;

import java.util.Map;
import static java.util.Map.entry;

public final class TypeMap {
    public static final Map<String, Class<?>> aliasToClass = Map.ofEntries(
            entry("boolean", Boolean.class),
            entry("byte", Byte.class),
            entry("bytes", byte[].class),
            entry("binary", byte[].class),
            entry("hex", byte[].class), // optional
            entry("short", Short.class),
            entry("integer", Integer.class),
            entry("long", Long.class),
            entry("float", Float.class),
            entry("double", Double.class),
            entry("string", String.class),
            entry("text", String.class),
            entry("json", com.fasterxml.jackson.databind.JsonNode.class),
            entry("xml", org.w3c.dom.Document.class),
            entry("date", java.time.LocalDate.class),
            entry("datetime", java.time.LocalDateTime.class),
            entry("protobuf", com.google.protobuf.ByteString.class),
            entry("any", com.google.protobuf.Any.class));

    public static Class<?> resolve(String alias) {
        Class<?> typeClass = aliasToClass.get(alias.toLowerCase());
        if (typeClass == null)
            throw new IllegalArgumentException("Unknown type: " + alias);
        return typeClass;
    }
}
