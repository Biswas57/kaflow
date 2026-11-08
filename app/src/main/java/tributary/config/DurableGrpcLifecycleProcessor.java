package tributary.config;

import kaflow.storage.DurableBroker;
import net.devh.boot.grpc.server.serverfactory.GrpcServerFactory;
import net.devh.boot.grpc.server.serverfactory.GrpcServerLifecycle;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;

/** Preserve the starter's conditional factory/service discovery, then replace its shutdown owner. */
final class DurableGrpcLifecycleProcessor implements DestructionAwareBeanPostProcessor, ApplicationContextAware {
    private ConfigurableApplicationContext context;
    private DurableGrpcLifecycle lifecycle;
    @Override public void setApplicationContext(ApplicationContext context) throws BeansException {
        this.context = (ConfigurableApplicationContext) context;
    }
    @Override public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof GrpcServerLifecycle)) return bean;
        if (lifecycle != null) throw new IllegalStateException("multiple durable gRPC lifecycle owners");
        lifecycle = new DurableGrpcLifecycle(context.getBean(GrpcServerFactory.class), context, context.getBean(DurableBroker.class));
        context.addApplicationListener(lifecycle);
        return lifecycle;
    }
    @Override public boolean requiresDestruction(Object bean) { return bean instanceof GrpcServerLifecycle; }
    @Override public void postProcessBeforeDestruction(Object bean, String name) { if (lifecycle != null) lifecycle.destroy(); }
}
