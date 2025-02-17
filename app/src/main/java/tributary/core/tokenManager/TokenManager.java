package tributary.core.tokenManager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class TokenManager {
    private static String ADMIN_KEY;
    private String adminProdToken;
    private String adminConsToken;

    public TokenManager(String password) {
        // Set the ADMIN_KEY only if it hasn't been set before.
        if (ADMIN_KEY == null) {
            ADMIN_KEY = password;
        } else {
            throw new IllegalStateException("Admin key has already been initialized.");
        }
    }

    public static String generateToken(String id, long timestamp) {
        if (ADMIN_KEY == null) {
            throw new IllegalStateException("Admin key is not initialized.");
        }
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

    public String getAdminConsToken() {
        return adminConsToken;
    }

    public void setAdminConsToken(String newConsToken) {
        this.adminConsToken = newConsToken;
    }

    public String getAdminProdToken() {
        return adminProdToken;
    }

    public void setAdminProdToken(String newProdToken) {
        this.adminProdToken = newProdToken;
    }
}
