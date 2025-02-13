package tributary.core.encryptionManager;

import java.util.Random;

public class PrimeNumGenerator {
    public static long generatePrime() {
        int prime;
            while (true)
            {
                int count = 0;
                double x  = Math.random();
                double y  = 10000 * x;
                double z  = Math.ceil(y);
                prime     = (int)z;
                for (int i = 1; i <= prime; i++)
                {
                    int modfactor = prime % i;
                    if (modfactor == 0)
                    {
                        count++;
                    }
                }
                if (count == 2)
                {
                    break;
                }
            }
        return prime;
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

    public static long gcd(long a, long b) {
        while (b != 0) {
            long temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }
}