package tributary.core.typeHandlerFactory;

public interface TypeHandler<T> {
    T handle(Object value);

    public String valueToString(Object value);

    T stringToValue(String value);
}
