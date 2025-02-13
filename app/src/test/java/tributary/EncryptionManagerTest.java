package tributary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tributary.core.encryptionManager.EncryptionManager;
import tributary.core.encryptionManager.PrimeNumGenerator;
import tributary.core.typeHandlerFactory.*;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionManagerTest {
    private EncryptionManager encryptionManager;
    private long publicKey;

    @BeforeEach
    void setUp() {
        encryptionManager = new EncryptionManager();
        publicKey = encryptionManager.getPublicKey();
    }

    @Test
    void testEncryptionDecryptionCycle() {
        String message = "123";
        String encryptedMessage = encryptionManager.encrypt(message);
        String decryptedMessage = encryptionManager.decrypt(encryptedMessage, publicKey);

        assertEquals(message, decryptedMessage, "Decryption should recover the original message");
    }

    @Test
    void testEncryptionDecryptionWithEdgeCases() {
        String[] testMessages = { "A", "Edge case with large numbers 123456789", "0" };

        for (String message : testMessages) {
            String encryptedMessage = encryptionManager.encrypt(message);
            String decryptedMessage = encryptionManager.decrypt(encryptedMessage, publicKey);

            assertEquals(message, decryptedMessage,
                    "Decryption should recover the original message for edge case: " + message);
        }
    }

    @Test
    void testModularExponentiation() {
        long base = 4;
        long exp = 13;
        long mod = 497;
        long expected = 445;
        long result = EncryptionManager.modularExponentiation(base, exp, mod);

        assertEquals(expected, result, "Modular exponentiation result is incorrect");
    }

    @Test
    void testModularExponentiationEdgeCases() {
        assertEquals(1, EncryptionManager.modularExponentiation(5, 0, 13), "Any number^0 mod N should be 1");
        assertEquals(0, EncryptionManager.modularExponentiation(0, 5, 13), "0^exp mod N should be 0");
        assertEquals(5, EncryptionManager.modularExponentiation(5, 1, 13),
                "Number^1 mod N should return the number itself mod N");
    }

    @Test
    void testModularInverse() {
        long a = 3;
        long m = 11;
        long expected = 4;
        long result = EncryptionManager.modularInverse(a, m);

        assertEquals(expected, result, "Modular inverse calculation is incorrect");
    }

    @Test
    void testModularInverseEdgeCases() {
        assertEquals(1, EncryptionManager.modularInverse(1, 13), "Modular inverse of 1 mod any number should be 1");
        assertThrows(ArithmeticException.class, () -> EncryptionManager.modularInverse(2, 4),
                "Modular inverse should not exist when a and m are not coprime");
    }

    @Test
    void testGenerateCoprime() {
        long n = 40;
        long coprime = PrimeNumGenerator.generateCoprime(n);

        assertEquals(1, PrimeNumGenerator.gcd(n, coprime), "Generated coprime should have a GCD of 1 with n");
    }

    @Test
    void testGenerateCoprimeEdgeCases() {
        long n = 2;
        long coprime = PrimeNumGenerator.generateCoprime(n);

        assertEquals(1, PrimeNumGenerator.gcd(n, coprime), "Generated coprime for n=2 should have a GCD of 1 with n");
    }

    @Test
    void testGCD() {
        assertEquals(5, PrimeNumGenerator.gcd(35, 10), "GCD of 35 and 10 should be 5");
        assertEquals(1, PrimeNumGenerator.gcd(17, 4), "GCD of 17 and 4 should be 1 (coprime)");
        assertEquals(4, PrimeNumGenerator.gcd(8, 4), "GCD of 8 and 4 should be 4");
    }

    @Test
    void testEntireFlow() {
        String originalMessage = "Hello World";
        String encryptedMessage = encryptionManager.encrypt(originalMessage);
        assertNotEquals(originalMessage, encryptedMessage, "Encrypted message should differ from the original message");
        String decryptedMessage = encryptionManager.decrypt(encryptedMessage, publicKey);
        assertEquals(originalMessage, decryptedMessage, "Decryption should correctly retrieve the original message");
    }

    @Test
    void testEntireFlowWithSpecialCharacters() {
        String originalMessage = "Encryption test! @#$%^&*()";
        String encryptedMessage = encryptionManager.encrypt(originalMessage);
        assertNotEquals(originalMessage, encryptedMessage, "Encrypted message should differ from the original message");
        String decryptedMessage = encryptionManager.decrypt(encryptedMessage, publicKey);
        assertEquals(originalMessage, decryptedMessage, "Decryption should correctly retrieve the original message");
    }

    @Test
    void testEntireFlowIntegerHandler() {
        IntegerHandler handler = new IntegerHandler();
        Integer payload = 123456;

        String payloadString = handler.valueToString(payload);
        String encrypted = encryptionManager.encrypt(payloadString);
        String decrypted = encryptionManager.decrypt(encrypted, publicKey);
        assertEquals(payloadString, decrypted);

        Integer processedPayload = handler.stringToValue(decrypted);
        assertEquals(payload, processedPayload);
    }

    @Test
    void testLargeIntegerEncryptionDecryption() {
        IntegerHandler handler = new IntegerHandler();
        Integer largeInteger = Integer.MAX_VALUE;
        String payloadString = handler.valueToString(largeInteger);
        String encrypted = encryptionManager.encrypt(payloadString);
        String decrypted = encryptionManager.decrypt(encrypted, publicKey);
        assertEquals(payloadString, decrypted);
        Integer processedPayload = handler.stringToValue(decrypted);
        assertEquals(largeInteger, processedPayload);
    }

    @Test
    void testMultiPartStringEncryptionDecryption() {
        StringHandler handler = new StringHandler();
        String message = "This is a test message with multiple words and numbers: 1234567890";
        String payloadString = handler.valueToString(message);
        String encrypted = encryptionManager.encrypt(payloadString);
        String decrypted = encryptionManager.decrypt(encrypted, publicKey);
        assertEquals(payloadString, decrypted);
        assertEquals(message, handler.stringToValue(decrypted));
    }

    @Test
    void testIntegerBoundaryValues() {
        IntegerHandler handler = new IntegerHandler();
        Integer[] edgeCases = { Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 0, 1 };
        for (Integer value : edgeCases) {
            String payloadString = handler.valueToString(value);
            String encrypted = encryptionManager.encrypt(payloadString);
            String decrypted = encryptionManager.decrypt(encrypted, publicKey);
            assertEquals(payloadString, decrypted);
            assertEquals(value, handler.stringToValue(decrypted));
        }
    }

    @Test
    void testExtendedSpecialCharactersInString() {
        StringHandler handler = new StringHandler();
        String specialMessage = "Encryption test with symbols: ☺️🌟✨🎉🚀💻!";
        String payloadString = handler.valueToString(specialMessage);
        String encrypted = encryptionManager.encrypt(payloadString);
        String decrypted = encryptionManager.decrypt(encrypted, publicKey);
        assertEquals(payloadString, decrypted);
        assertEquals(specialMessage, handler.stringToValue(decrypted));
    }

    @Test
    void testVeryLargeStringEncryptionDecryption() {
        StringHandler handler = new StringHandler();
        StringBuilder largeMessageBuilder = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeMessageBuilder.append("Repeated pattern 12345 ");
        }
        String largeMessage = largeMessageBuilder.toString();
        String payloadString = handler.valueToString(largeMessage);
        String encrypted = encryptionManager.encrypt(payloadString);
        String decrypted = encryptionManager.decrypt(encrypted, publicKey);
        assertEquals(payloadString, decrypted);
        assertEquals(largeMessage, handler.stringToValue(decrypted));
    }

    @Test
    void testComplexStringWithMixedContent() {
        StringHandler handler = new StringHandler();
        String complexMessage = "Complex123StringWith@Special#Characters&More1234567890Symbols%$*";
        String payloadString = handler.valueToString(complexMessage);
        String encrypted = encryptionManager.encrypt(payloadString);
        String decrypted = encryptionManager.decrypt(encrypted, publicKey);
        assertEquals(payloadString, decrypted);
        assertEquals(complexMessage, handler.stringToValue(decrypted));
    }

    @Test
    void testNumericStringEncryptionDecryption() {
        StringHandler handler = new StringHandler();
        String numericString = "9876543210123456789";
        String payloadString = handler.valueToString(numericString);
        String encrypted = encryptionManager.encrypt(payloadString);
        String decrypted = encryptionManager.decrypt(encrypted, publicKey);
        assertEquals(payloadString, decrypted);
        assertEquals(numericString, handler.stringToValue(decrypted));
    }

    /*
     * This is to check that even when decrypting the 
     */
    @Test
    void testDecryptOnly() {
        IntegerHandler handler = new IntegerHandler();
        String payload5 = "831";
        publicKey = 39599;

        String decrypted = encryptionManager.decrypt(payload5, publicKey);
        assertEquals("5", decrypted);

        int number = handler.stringToValue(decrypted);
        assertEquals(5, number);

        String payload100 = "615 990 990";
        publicKey = 39599;

        decrypted = encryptionManager.decrypt(payload100, publicKey);
        assertEquals("100", decrypted);

        number = handler.stringToValue(decrypted);
        assertEquals(100, number);
    }
}
