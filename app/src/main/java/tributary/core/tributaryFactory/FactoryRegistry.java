package tributary.core.tributaryFactory;

import java.util.HashMap;
import java.util.Map;

/*
 * This class is resposible for updating all types of possible factories
 * If I want to store a 
 */
public final class FactoryRegistry {

    private static final Map<Class<?>, ObjectFactory<?>> byClass = new HashMap<>();

    static {
        register(Integer.class);
        register(String.class);
        register(byte[].class);
    }

    public static <T> ObjectFactory<T> get(Class<T> c) {
        @SuppressWarnings("unchecked")
        ObjectFactory<T> f = (ObjectFactory<T>) byClass.get(c);
        if (f == null)
            throw new IllegalArgumentException("No factory for " + c);
        return f;
    }

    private static <T> void register(Class<T> c) {
        byClass.put(c, new GenericFactory<>(c));
    }
}
