package tributary.core.typeHandlerFactory;

import java.util.HashMap;
import java.util.Map;

public class TypeHandlerFactory {
    private static Map<Class<?>, TypeHandler<?>> handlers = new HashMap<>();

    static {
        handlers.put(Integer.class, new IntegerHandler());
        handlers.put(String.class, new StringHandler());
    }

    @SuppressWarnings("unchecked")
    public static <T> TypeHandler<T> getHandler(Class<T> type) {
        return (TypeHandler<T>) handlers.get(type);
    }
}
