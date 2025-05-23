// package tributary;

// import org.junit.jupiter.api.Test;
// import static org.junit.jupiter.api.Assertions.*;
// import java.nio.charset.StandardCharsets;

// import tributary.core.typeHandlerFactory.*;

// class TypeHandlerTest {

// @Test
// void testStringHandler() {
// TypeHandler<String> stringHandler = new StringHandler();

// // Test valueToLong with a short string
// String message = "Hello";
// String stringAsLong = stringHandler.valueToString(message);
// String convertedBack = stringHandler.stringToValue(stringAsLong);

// assertEquals(message, convertedBack, "String conversion to long and back
// should preserve value");

// // Test valueToLong with UTF-8 special characters
// String utf8String = "こん"; // "Hello" in Japanese
// stringAsLong = stringHandler.valueToString(utf8String);
// convertedBack = stringHandler.stringToValue(stringAsLong);

// assertEquals(utf8String.substring(0, Math.min(utf8String.length(), 8)),
// convertedBack.trim(),
// "UTF-8 encoded string conversion should preserve value");
// }

// @Test
// void testIntegerHandler() {
// TypeHandler<Integer> integerHandler = new IntegerHandler();

// // Test valueToLong with Integer value
// Integer number = 123456;
// String integerAsLong = integerHandler.valueToString(number);
// Integer convertedBack = integerHandler.stringToValue(integerAsLong);

// assertEquals(number, convertedBack, "Integer conversion to long and back
// should preserve value");
// }

// @Test
// void testByteHandler() {
// TypeHandler<byte[]> byteHandler = new ByteHandler();

// // Test valueToLong with UTF-8 encoded byte array
// String message = "Hello";
// byte[] byteArray = message.getBytes(StandardCharsets.UTF_8);
// String byteArrayAsString = byteHandler.valueToString(byteArray);
// byte[] convertedBack = byteHandler.stringToValue(byteArrayAsString);

// assertArrayEquals(byteArray, convertedBack, "Byte array conversion to long
// and back should preserve value");

// // Test valueToLong with a different string message in byte array format
// byte[] utf8Bytes = "こんにちは".getBytes(StandardCharsets.UTF_8); // "Hello" in
// byteArrayAsString = byteHandler.valueToString(utf8Bytes);
// convertedBack = byteHandler.stringToValue(byteArrayAsString);

// // Ensure that the resulting byte array matches the input (up to 8 bytes)
// assertArrayEquals(utf8Bytes, convertedBack, "UTF-8 byte array conversion
// should preserve value");
// }

// @Test
// void testTypeHandlerFactory() {
// TypeHandler<Integer> integerHandler =
// TypeHandlerFactory.getHandler(Integer.class);
// TypeHandler<String> stringHandler =
// TypeHandlerFactory.getHandler(String.class);
// TypeHandler<byte[]> byteArrayHandler =
// TypeHandlerFactory.getHandler(byte[].class);

// assertNotNull(integerHandler, "TypeHandlerFactory should return an
// IntegerHandler for Integer.class");
// assertNotNull(stringHandler, "TypeHandlerFactory should return a
// StringHandler for String.class");
// assertNotNull(byteArrayHandler, "TypeHandlerFactory should return a
// ByteHandler for byte[].class");

// // Validate each handler's basic functionality using TypeHandlerFactory
// assertEquals("123", integerHandler.valueToString(123));
// assertEquals("Hello",
// stringHandler.stringToValue(stringHandler.valueToString("Hello")));

// // Test byte array conversion
// byte[] originalBytes = "Hello".getBytes(StandardCharsets.UTF_8);
// byte[] expectedBytes = new byte[8];
// System.arraycopy(originalBytes, 0, expectedBytes, 0, Math.min(8,
// originalBytes.length));

// }
// }
