package tributary.core.util;

import java.util.Map;
import static java.util.Map.entry;
import com.google.protobuf.*;

public final class TypeMap {
    public static final Map<String, Class<?>> aliasToClass = Map.ofEntries(
            entry("boolean", Boolean.class),
            entry("byte", Byte.class),
            entry("bytes", byte[].class),
            entry("integer", Integer.class),
            entry("int", Integer.class),
            entry("long", Long.class),
            entry("float", Float.class),
            entry("double", Double.class),
            entry("string", String.class),
            entry("text", String.class),
            entry("any", Any.class));

    public static Class<?> resolve(String alias) {
        Class<?> typeClass = aliasToClass.get(alias.toLowerCase());
        if (typeClass == null)
            throw new IllegalArgumentException("Unknown type: " + alias);
        return typeClass;
    }

    // Conversion helpers
    public static byte[] convert(BytesValue byteValue) {
        return byteValue.getValue().toByteArray();
    }

    public static Boolean convert(BoolValue boolValue) {
        return boolValue.getValue();
    }

    public static String convert(StringValue stringValue) {
        return stringValue.getValue();
    }

    public static Integer convert(Int32Value intValue) {
        return intValue.getValue();
    }

    public static Long convert(Int64Value longValue) {
        return longValue.getValue();
    }

    public static Float convert(FloatValue floatValue) {
        return floatValue.getValue();
    }

    public static Double convert(DoubleValue doubleValue) {
        return doubleValue.getValue();
    }
}
