package id.ac.ui.cs.advprog.bidmartgateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Validates that identity fields in JSON mutation bodies match the trusted gateway header.
 */
public final class IdentityBodyConflictChecker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IdentityBodyConflictChecker() {
    }

    public static Optional<String> findConflict(String trustedUserId, byte[] bodyBytes) {
        if (trustedUserId == null || trustedUserId.isBlank() || bodyBytes == null || bodyBytes.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(bodyBytes);
            if (!root.isObject()) {
                return Optional.empty();
            }
            return firstConflict(trustedUserId, root);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> firstConflict(String trustedUserId, JsonNode root) {
        for (String field : new String[]{"sellerId", "userId", "bidderId"}) {
            JsonNode value = root.get(field);
            if (value != null && value.isTextual()) {
                String bodyValue = value.asText();
                if (!bodyValue.isBlank() && !trustedUserId.equals(bodyValue)) {
                    return Optional.of(field);
                }
            }
        }
        return Optional.empty();
    }
}
