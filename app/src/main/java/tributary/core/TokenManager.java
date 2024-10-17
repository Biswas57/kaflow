package tributary.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Scanner;

public class TokenManager {
    private static final String ADMIN_KEY = System.getenv("SECRET_KEY");

    public static String generateToken(String id, long timestamp) {
        String data = id + ":" + timestamp + ":" + ADMIN_KEY;
        return hashSHA256(data);
    }

    private static String hashSHA256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean validateToken(String token, String adminId, long adminTimeCreated) {
        System.out.println("Enter the password for the Tributary Admin: ");
        Scanner scanner = new Scanner(System.in);
        String password = scanner.nextLine();
        scanner.close();

        String data = adminId + ":" + adminTimeCreated + ":" + password;
        return hashSHA256(data).equals(token);
    }
}
