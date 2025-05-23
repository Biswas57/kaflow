package tributary.core.tributaryFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/*
 * This class is resposible for updating all types of possible factories
 * If I want to store a 
 */
public final class FactoryRegistry {

    private static final ConcurrentMap<Class<?>, ObjectFactory<?>> byClass = new ConcurrentHashMap<>();

    public static <T> ObjectFactory<T> get(Class<T> factoryClass) {
        @SuppressWarnings("unchecked")
        ObjectFactory<T> factory = (ObjectFactory<T>) byClass.get(factoryClass);
        if (factory == null)
            register(factoryClass);
        return factory;
    }

    private static <T> void register(Class<T> factoryClass) {
        byClass.put(factoryClass, new GenericFactory<>(factoryClass));
    }
}
