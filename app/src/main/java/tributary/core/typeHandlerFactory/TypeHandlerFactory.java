package tributary.core.typeHandlerFactory;

import java.util.HashMap;
import java.util.Map;

public class TypeHandlerFactory<T> {
    private static Map<Class<?>, TypeHandler<?>> handlers = new HashMap<>();

    static {
        handlers.put(Integer.class, new IntegerHandler());
        handlers.put(String.class, new StringHandler());
        handlers.put(byte[].class, new ByteHandler());
    }

    @SuppressWarnings("unchecked")
    public static <T> TypeHandler<T> getHandler(Class<T> type) {
        TypeHandler<?> raw = handlers.get(type);
        if (raw == null) {
            throw new IllegalArgumentException("No handler registered for " + type);
        }
        return (TypeHandler<T>) raw;
    }

    /**
     * If you ever need to add new handlers at runtime:
     */
    public static <T> void registerHandler(Class<T> type, TypeHandler<T> handler) {
        handlers.put(type, handler);
    }
}
