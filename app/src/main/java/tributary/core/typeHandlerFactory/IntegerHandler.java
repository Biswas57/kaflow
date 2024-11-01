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

    @Override
    public String valueToString(Object value) {
        return Integer.toString(handle(value));
    }

    @Override
    public Integer stringToValue(String value) {
        return Integer.parseInt(value);
    }
}
