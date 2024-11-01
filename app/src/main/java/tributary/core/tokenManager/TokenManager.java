package tributary.core.tokenManager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import io.github.cdimascio.dotenv.Dotenv;

public class TokenManager {
    private static final Dotenv dotenv = Dotenv.load();
    private static final String ADMIN_KEY = dotenv.get("SECRET_KEY");

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

    public static boolean validateToken(String token, String adminId, long adminTimeCreated, String password) {
        String data = adminId + ":" + adminTimeCreated + ":" + password;
        return hashSHA256(data).equals(token);
    }
}
