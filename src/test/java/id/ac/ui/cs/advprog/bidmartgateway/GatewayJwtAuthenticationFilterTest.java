package id.ac.ui.cs.advprog.bidmartgateway;

import id.ac.ui.cs.advprog.bidmartgateway.security.AuthPermissionClient;
import id.ac.ui.cs.advprog.bidmartgateway.security.GatewayJwtAuthenticationFilter;
import id.ac.ui.cs.advprog.bidmartgateway.security.RoutePermissionPolicy;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayJwtAuthenticationFilterTest {

    private static final String SECRET = "bidmart-auth-secret-key-bidmart-auth-secret-key";
    private static final String INTERNAL_TOKEN = "internal-gateway-token";
    private static final RoutePermissionPolicy ROUTE_PERMISSION_POLICY = new RoutePermissionPolicy();

    @Test
    void protectedRouteShouldRejectMissingJwt() {
        GatewayJwtAuthenticationFilter filter = new GatewayJwtAuthenticationFilter(
                permissionClient(true),
                ROUTE_PERMISSION_POLICY,
                SECRET,
                INTERNAL_TOKEN
        );
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auctions")
        );

        filter.filter(exchange, successChain()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void protectedRouteShouldForwardVerifiedIdentityAndStripSpoofedHeaders() {
        GatewayJwtAuthenticationFilter filter = new GatewayJwtAuthenticationFilter(
                permissionClient(true),
                ROUTE_PERMISSION_POLICY,
                SECRET,
                INTERNAL_TOKEN
        );
        String token = token("user-1", "buyer@test.com", List.of("BUYER"));
        AtomicReference<ServerWebExchange> forwardedExchange = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auctions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-User-Id", "spoofed-user")
                        .header("X-User-Email", "spoofed@test.com")
                        .header("X-User-Roles", "ADMIN")
        );

        filter.filter(exchange, captureChain(forwardedExchange)).block();

        HttpHeaders headers = forwardedExchange.get().getRequest().getHeaders();
        assertEquals("user-1", headers.getFirst("X-User-Id"));
        assertEquals("buyer@test.com", headers.getFirst("X-User-Email"));
        assertEquals("BUYER", headers.getFirst("X-User-Roles"));
        assertEquals(INTERNAL_TOKEN, headers.getFirst("X-Internal-Service-Token"));
        assertTrue(headers.containsKey("X-Correlation-Id"));
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void protectedRouteShouldPreserveExistingCorrelationId() {
        GatewayJwtAuthenticationFilter filter = new GatewayJwtAuthenticationFilter(
                permissionClient(true),
                ROUTE_PERMISSION_POLICY,
                SECRET,
                INTERNAL_TOKEN
        );
        String token = token("user-1", "buyer@test.com", List.of("BUYER"));
        AtomicReference<ServerWebExchange> forwardedExchange = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auctions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-Correlation-Id", "request-123")
        );

        filter.filter(exchange, captureChain(forwardedExchange)).block();

        assertEquals("request-123", forwardedExchange.get().getRequest().getHeaders().getFirst("X-Correlation-Id"));
    }

    @Test
    void routeShouldRejectJwtWhenRequiredPermissionIsDenied() {
        GatewayJwtAuthenticationFilter filter = new GatewayJwtAuthenticationFilter(
                permissionClient(false),
                ROUTE_PERMISSION_POLICY,
                SECRET,
                INTERNAL_TOKEN
        );
        String token = token("seller-1", "seller@test.com", List.of("SELLER"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.POST, "/api/v1/auctions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        );

        filter.filter(exchange, successChain()).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    private AuthPermissionClient permissionClient(boolean allowed) {
        return (email, permission) -> Mono.just(allowed);
    }

    private GatewayFilterChain successChain() {
        return exchange -> {
            assertTrue(true);
            return Mono.empty();
        };
    }

    private GatewayFilterChain captureChain(AtomicReference<ServerWebExchange> capturedExchange) {
        return exchange -> {
            capturedExchange.set(exchange);
            return Mono.empty();
        };
    }

    private String token(String subject, String email, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .claim("email", email)
                .claim("roles", roles)
                .claim("type", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
