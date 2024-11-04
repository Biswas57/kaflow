package tributary.core.tokenManager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import io.github.cdimascio.dotenv.Dotenv;

public class TokenManager {
    private static final Dotenv dotenv = Dotenv.load();
    private static final String ADMIN_KEY = dotenv.get("SECRET_KEY");

    public static String generateToken(String id, long timestamp) {
        String data = id + ":" + timestamp + ":" + ADMIN_KEY;
        return algorithm(data);
    }

    private static String algorithm(String data) {
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

    public static boolean validateToken(String token, String adminId, long adminBirthTime, String password) {
        String data = adminId + ":" + adminBirthTime + ":" + password;
        return algorithm(data).equals(token);
    }
}
