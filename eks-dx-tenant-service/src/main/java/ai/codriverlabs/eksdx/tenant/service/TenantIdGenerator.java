package ai.codriverlabs.eksdx.tenant.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Generates stable, opaque tenant IDs from IAM Identity Center user IDs.
 *
 * tenantId = HEX(SHA256(idcUserId + ":" + createdAt))[:8]
 *
 * The createdAt component ensures uniqueness across recreations for the same user.
 * On the rare DynamoDB conditional-check collision, callers should extend to 9 chars.
 */
@ApplicationScoped
public class TenantIdGenerator {

    /**
     * Generate an 8-character hex tenant ID.
     *
     * @param idcUserId  IAM Identity Center user ID (non-null, non-blank)
     * @param createdAt  ISO-8601 UTC creation timestamp (non-null, non-blank)
     * @return 8-char lowercase hex string
     */
    public String generate(String idcUserId, String createdAt) {
        return hexHash(idcUserId, createdAt, 8);
    }

    /**
     * Generate a 9-character hex tenant ID for collision retry.
     */
    public String generateExtended(String idcUserId, String createdAt) {
        return hexHash(idcUserId, createdAt, 9);
    }

    private String hexHash(String idcUserId, String createdAt, int length) {
        if (idcUserId == null || idcUserId.isBlank()) throw new IllegalArgumentException("idcUserId must not be blank");
        if (createdAt == null || createdAt.isBlank()) throw new IllegalArgumentException("createdAt must not be blank");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((idcUserId + ":" + createdAt).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < (length + 1) / 2; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.substring(0, length);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
