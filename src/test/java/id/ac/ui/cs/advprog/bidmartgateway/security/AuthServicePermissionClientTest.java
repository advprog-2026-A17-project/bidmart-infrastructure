package id.ac.ui.cs.advprog.bidmartgateway.security;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServicePermissionClientTest {

    private MockWebServer mockWebServer;
    private AuthServicePermissionClient client;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        client = new AuthServicePermissionClient(
                WebClient.builder(),
                mockWebServer.url("/").toString().replaceAll("/$", "")
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void hasPermissionReturnsTrueWhenAuthAllows() {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"allowed\":true}")
                .addHeader("Content-Type", "application/json"));

        Boolean allowed = client.hasPermission("user@test.com", "bid:place").block();

        assertTrue(allowed);
    }

    @Test
    void hasPermissionReturnsFalseOnError() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        Boolean allowed = client.hasPermission("user@test.com", "bid:place").block();

        assertFalse(allowed);
    }
}
