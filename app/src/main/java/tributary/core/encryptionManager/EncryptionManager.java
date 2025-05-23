package tributary.core.encryptionManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import tributary.core.util.Pair;

@Deprecated
public class EncryptionManager {
    private final long n; // Modulus for public and private keys
    private final long totient; // Euler's totient phi(N)
    private final long e; // Public key exponent
    private long p1;
    private long p2;

    // Excryption manager for producers (create private keys for partitions)
    public EncryptionManager() {
        p1 = PrimeNumGenerator.generatePrime();
        p2 = PrimeNumGenerator.generatePrime();

        // Calculate modulus N (private key)
        // int p1 = PrimeNumGenerator.generatePrime();
        // int p2 = PrimeNumGenerator.generatePrime();
        n = p1 * p2;

        // Calculate Euler's totient φ(N) (private key)
        totient = getEulersTotient(p1, p2);

        // Choose e (public key) coprime with totient
        e = PrimeNumGenerator.generateCoprime(totient);
    }

    // Excryption manager for consumer (ensure decryption with the same keyx)
    public EncryptionManager(Pair<Long, Long> primePair) {
        p1 = primePair.left();
        p2 = primePair.right();

        // Calculate modulus N (private key)
        // int p1 = PrimeNumGenerator.generatePrime();
        // int p2 = PrimeNumGenerator.generatePrime();
        n = p1 * p2;

        // Calculate Euler's totient φ(N) (private key)
        totient = getEulersTotient(p1, p2);

        // Choose e (public key) coprime with totient
        e = PrimeNumGenerator.generateCoprime(totient);
    }

    // RSA encryption with refined encoding
    public byte[] encrypt(byte[] plain) {

        List<Long> blocks = toLongBlocks(plain, true); // pad last block
        ByteArrayOutputStream out = new ByteArrayOutputStream(blocks.size() * 8);

        for (long m : blocks) {
            // ciphertext = byte^e mod n
            long c = modularExponentiation(m, e, n);
            writeLong(out, c);
        }
        return out.toByteArray();
    }

    // RSA decryption with refined decoding
    public byte[] decrypt(byte[] cipher) {

        if (cipher.length % 8 != 0)
            throw new IllegalArgumentException("cipher length must be multiple of 8");

        ByteBuffer buf = ByteBuffer.wrap(cipher);
        ByteArrayOutputStream plain = new ByteArrayOutputStream(cipher.length);
        // plaintext = ciphertext^d mod n
        long d = modularInverse(e, totient);

        while (buf.hasRemaining()) {
            long c = buf.getLong();
            long m = modularExponentiation(c, d, n);
            writeLong(plain, m);
        }
        return trimPadding(plain.toByteArray());
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

    public long getModulus() {
        return n;
    }

    public long getEulersTotient(long prime1, long prime2) {
        return (prime1 - 1) * (prime2 - 1);
    }

    public long getPublicKey() {
        return e;
    }

    public Pair<Long, Long> getPrimePair() {
        return new Pair<>(p1, p2);
    }

    // helpers
    private static List<Long> toLongBlocks(byte[] data, boolean pad) {
        List<Long> blocks = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(data);

        while (buf.remaining() >= 8) {
            blocks.add(buf.getLong());
        }

        int rem = buf.remaining();
        if (rem > 0 || pad) {
            byte[] last = new byte[8];
            if (rem > 0)
                buf.get(last, 0, rem);
            blocks.add(ByteBuffer.wrap(last).getLong());
        }
        return blocks;
    }

    private static void writeLong(OutputStream out, long value) {
        try {
            out.write(ByteBuffer.allocate(8).putLong(value).array());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] trimPadding(byte[] bytes) {
        int i = bytes.length - 1;
        while (i >= 0 && bytes[i] == 0)
            i--; // remove trailing 0-padding
        return Arrays.copyOf(bytes, i + 1);
    }
}
