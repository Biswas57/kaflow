package tributary.core.typeHandlerFactory;

public class StringHandler implements TypeHandler<String> {
    @Override
    public String handle(Object value) {
        return value.toString();
    }

    @Override
    public String valueToString(Object value) {
        return value.toString();
    }

    @Override
    public String stringToValue(String value) {
        return value;
    }
}
