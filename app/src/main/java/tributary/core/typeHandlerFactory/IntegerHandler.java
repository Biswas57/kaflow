package tributary.core.typeHandlerFactory;

public class IntegerHandler implements TypeHandler<Integer> {
    @Override
    public Integer handle(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        } else {
            throw new IllegalArgumentException("Value is not a Number: " + value);
        }
    }
}
