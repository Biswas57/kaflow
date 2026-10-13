package tributary.core.tributaryFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import tributary.core.tributaryObject.TributaryCluster;

/** Factories are scoped to a single injected cluster, never cached process-wide. */
public final class FactoryRegistry {
    private final TributaryCluster cluster;
    private final ConcurrentMap<Class<?>, ObjectFactory<?>> byClass = new ConcurrentHashMap<>();

    public FactoryRegistry(TributaryCluster cluster) {
        this.cluster = Objects.requireNonNull(cluster);
    }

    @SuppressWarnings("unchecked")
    public <T> ObjectFactory<T> get(Class<T> factoryClass) {
        return (ObjectFactory<T>) byClass.computeIfAbsent(factoryClass,
                type -> new GenericFactory<>(type, cluster));
    }
}
