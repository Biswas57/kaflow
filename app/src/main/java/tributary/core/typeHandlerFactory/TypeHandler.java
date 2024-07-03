package tributary.core.typeHandlerFactory;

public interface TypeHandler<T> {
    T handle(Object value);
}
