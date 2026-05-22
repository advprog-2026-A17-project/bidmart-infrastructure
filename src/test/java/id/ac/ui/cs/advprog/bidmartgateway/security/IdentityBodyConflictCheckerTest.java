package id.ac.ui.cs.advprog.bidmartgateway.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityBodyConflictCheckerTest {

    @Test
    void shouldDetectConflictingSellerId() {
        byte[] body = "{\"sellerId\":\"other-seller\",\"title\":\"Laptop\"}"
                .getBytes(StandardCharsets.UTF_8);

        assertEquals("sellerId", IdentityBodyConflictChecker.findConflict("trusted-seller", body).orElseThrow());
    }

    @Test
    void shouldDetectConflictingUserIdOnWalletMutation() {
        byte[] body = "{\"userId\":\"other-user\",\"amountCents\":1000}"
                .getBytes(StandardCharsets.UTF_8);

        assertEquals("userId", IdentityBodyConflictChecker.findConflict("wallet-owner", body).orElseThrow());
    }

    @Test
    void shouldAllowMatchingBidderId() {
        byte[] body = "{\"bidderId\":\"bidder-1\",\"amountCents\":5000}"
                .getBytes(StandardCharsets.UTF_8);

        assertTrue(IdentityBodyConflictChecker.findConflict("bidder-1", body).isEmpty());
    }

    @Test
    void shouldIgnoreWhenBodyOmitsIdentityFields() {
        byte[] body = "{\"amountCents\":5000}".getBytes(StandardCharsets.UTF_8);

        assertTrue(IdentityBodyConflictChecker.findConflict("bidder-1", body).isEmpty());
    }
}
