package tributary.core.encryptionManager;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.github.cdimascio.dotenv.Dotenv;

public class EncryptionManager {
    private static final Dotenv dotenv = Dotenv.load();
    private static final String PRIME1 = dotenv.get("PRIME1");
    private static final String PRIME2 = dotenv.get("PRIME2");

    private final long n; // Modulus for public and private keys
    private final long totient; // Euler's totient phi(N)
    private final long e; // Public key exponent

    public EncryptionManager() {
        long p1 = Long.parseLong(PRIME1);
        long p2 = Long.parseLong(PRIME2);

        // Calculate modulus N
        n = p1 * p2;

        // Calculate Euler's totient φ(N)
        totient = getEulersTotient(p1, p2);

        // Choose e (public key) coprime with totient
        e = generateCoprime(totient);
    }

    // RSA encryption with refined encoding
    public String encrypt(String message) {
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        long[] encryptedArray = new long[messageBytes.length];
        StringBuilder encryptedMessage = new StringBuilder();

        for (int i = 0; i < messageBytes.length; i++) {
            // ciphertext = byte^e mod n
            encryptedArray[i] = modularExponentiation(messageBytes[i], e, n);
            encryptedMessage.append(encryptedArray[i]).append(" ");
        }

        return encryptedMessage.toString().trim();
    }

    // RSA decryption with refined decoding
    public String decrypt(String ciphertext, long e) {
        String[] ciphertextArray = ciphertext.split(" ");
        byte[] decryptedBytes = new byte[ciphertextArray.length];
        long d = modularInverse(e, totient);

        for (int i = 0; i < ciphertextArray.length; i++) {
            long decryptedLong = modularExponentiation(Long.parseLong(ciphertextArray[i]), d, n);
            decryptedBytes[i] = (byte) decryptedLong;
        }

        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }

    // Modular exponentiation: (base^exp) % mod.
    // You encrypt a message x using the encryption key (e,N) by simply calculating
    // x^e mod N.
    public static long modularExponentiation(long base, long exp, long mod) {
        long result = 1;
        base = base % mod;

        while (exp > 0) {
            if ((exp & 1) == 1) { // If exp is odd
                result = (result * base) % mod;
            }
            exp >>= 1; // exp = exp / 2
            base = (base * base) % mod;
        }

        return result;
    }

    // Extended Euclidean Algorithm to find modular
    // inverse of e mod phi(N)
    public static long modularInverse(long a, long m) {
        long m0 = m, x0 = 0, x1 = 1;

        if (m == 1)
            return 0;

        while (a > 1) {
            long q = a / m;
            long t = m;

            // m is remainder now, process
            // same as Euclid's algo
            m = a % m;
            a = t;
            t = x0;

            x0 = x1 - q * x0;
            x1 = t;
        }

        // Make x1 positive
        if (x1 < 0)
            x1 += m0;

        return x1;
    }

    // Helper function to generate a coprime of n
    public static long generateCoprime(long n) {
        Random rand = new Random();
        long coprime;

        do {
            coprime = rand.nextInt((int) (n - 1)) + 1;
        } while (gcd(n, coprime) != 1);

        return coprime;
    }

    // Helper function to calculate the GCD of two numbers
    public static long gcd(long a, long b) {
        while (b != 0) {
            long temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }

    public long getModulus() {
        return n;
    }

    public long getEulersTotient(long prime1, long prime2) {
        return (prime1 - 1) * (prime2 - 1);
    }

    public long getPublicKey() {
        return e;
    }

    public static long[] stringToLongArray(String input) {
        String[] words = input.split(" ");
        List<Long> longList = new ArrayList<>();

        for (String word : words) {
            byte[] wordBytes = word.getBytes(StandardCharsets.UTF_8);

            // Ensure the word can be represented within a single long (max 8 bytes)
            if (wordBytes.length > 8) {
                throw new IllegalArgumentException("Word is too long to convert to a single long value.");
            }

            // Pad the byte array to 8 bytes if it's shorter
            byte[] paddedBytes = new byte[8];
            System.arraycopy(wordBytes, 0, paddedBytes, 8 - wordBytes.length, wordBytes.length);

            // Convert the padded byte array to a long and add to list
            longList.add(ByteBuffer.wrap(paddedBytes).getLong());
        }

        return longList.stream().mapToLong(Long::longValue).toArray();
    }

    public static String longArrayToString(long[] longArray) {
        StringBuilder result = new StringBuilder();

        for (long l : longArray) {
            // Convert the long to a byte array
            byte[] bytes = ByteBuffer.allocate(8).putLong(l).array();

            // Trim leading zero bytes and convert remaining bytes back to a String
            String word = new String(bytes, StandardCharsets.UTF_8).trim();
            result.append(word).append(" ");
        }

        return result.toString().trim();
    }

    public static void main(String[] args) {
        EncryptionManager encryptionManager = new EncryptionManager();

        String originalMessage = "NFDUSIFNIOWENOIFW";
        System.out.println("Original Message: " + originalMessage);

        // Encrypt the message
        String encryptedMessage = encryptionManager.encrypt(originalMessage);
        System.out.println("Encrypted Message: " + encryptedMessage);

        // Decrypt the message
        String decryptedMessage = encryptionManager.decrypt(encryptedMessage, encryptionManager.getPublicKey());
        System.out.println("Decrypted Message: " + decryptedMessage);

        if (originalMessage.equals(decryptedMessage)) {
            System.out.println("Success: The decrypted message matches the original.");
        } else {
            System.out.println("Error: The decrypted message does not match the original.");
        }
    }
}
