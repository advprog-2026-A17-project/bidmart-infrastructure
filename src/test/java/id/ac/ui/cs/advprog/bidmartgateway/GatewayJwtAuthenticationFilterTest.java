package id.ac.ui.cs.advprog.bidmartgateway;

import id.ac.ui.cs.advprog.bidmartgateway.metrics.GatewayMetrics;
import id.ac.ui.cs.advprog.bidmartgateway.security.AuthPermissionClient;
import id.ac.ui.cs.advprog.bidmartgateway.security.GatewayJwtAuthenticationFilter;
import id.ac.ui.cs.advprog.bidmartgateway.security.RoutePermissionPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        GatewayJwtAuthenticationFilter filter = createFilter(permissionClient(true));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/listings")
        );

        filter.filter(exchange, successChain()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }


    @Test
    void protectedRouteShouldForwardVerifiedIdentityAndStripSpoofedHeaders() {
        GatewayJwtAuthenticationFilter filter = createFilter(permissionClient(true));
        String token = token("user-1", "buyer@test.com", List.of("BUYER"));
        AtomicReference<ServerWebExchange> forwardedExchange = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/orders")
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
    void validInternalServiceTokenShouldBypassJwtAndForwardToken() {
        GatewayJwtAuthenticationFilter filter = createFilter(permissionClient(false));
        AtomicReference<ServerWebExchange> forwardedExchange = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/wallet/seller-escrow")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .header("X-User-Id", "spoofed-user")
                        .header("X-User-Email", "spoofed@test.com")
                        .header("X-User-Roles", "ADMIN")
        );

        filter.filter(exchange, captureChain(forwardedExchange)).block();

        HttpHeaders headers = forwardedExchange.get().getRequest().getHeaders();
        assertEquals(INTERNAL_TOKEN, headers.getFirst("X-Internal-Service-Token"));
        assertNull(headers.getFirst("X-User-Id"));
        assertNull(headers.getFirst("X-User-Email"));
        assertNull(headers.getFirst("X-User-Roles"));
        assertTrue(headers.containsKey("X-Correlation-Id"));
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void invalidInternalServiceTokenShouldStillRequireJwt() {
        GatewayJwtAuthenticationFilter filter = createFilter(permissionClient(true));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/wallet/seller-escrow")
                        .header("X-Internal-Service-Token", "wrong-token")
        );

        filter.filter(exchange, successChain()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void protectedRouteShouldPreserveExistingCorrelationId() {
        GatewayJwtAuthenticationFilter filter = createFilter(permissionClient(true));
        String token = token("user-1", "buyer@test.com", List.of("BUYER"));
        AtomicReference<ServerWebExchange> forwardedExchange = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-Correlation-Id", "request-123")
        );

        filter.filter(exchange, captureChain(forwardedExchange)).block();

        assertEquals("request-123", forwardedExchange.get().getRequest().getHeaders().getFirst("X-Correlation-Id"));
    }

    @Test
    void walletRouteShouldAllowAdminWildcardWhenWalletPermissionMissing() {
        GatewayJwtAuthenticationFilter filter = createFilter(selectivePermissionClient());
        String token = token(
                "00000000-0000-0000-0000-300000000001",
                "admin@bidmart.com",
                List.of("ADMIN")
        );
        AtomicReference<ServerWebExchange> forwardedExchange = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/wallet/00000000-0000-0000-0000-300000000001/detail")
                        .queryParam("role", "BUYER")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        );

        filter.filter(exchange, captureChain(forwardedExchange)).block();

        assertNull(exchange.getResponse().getStatusCode());
        assertEquals("00000000-0000-0000-0000-300000000001", forwardedExchange.get().getRequest().getHeaders().getFirst("X-User-Id"));
    }

    @Test
    void routeShouldRejectJwtWhenRequiredPermissionIsDenied() {
        GatewayJwtAuthenticationFilter filter = createFilter(permissionClient(false));
        String token = token("seller-1", "seller@test.com", List.of("SELLER"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.POST, "/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        );

        filter.filter(exchange, successChain()).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void catalogueSearchShouldBePublicWithoutJwt() {
        GatewayJwtAuthenticationFilter filter = createFilter(permissionClient(true));
        AtomicReference<ServerWebExchange> forwardedExchange = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/catalogue/listings/search?keyword=laptop")
        );

        filter.filter(exchange, captureChain(forwardedExchange)).block();

        assertEquals("/api/v1/catalogue/listings/search", forwardedExchange.get().getRequest().getURI().getPath());
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void catalogueReadRoutesShouldBePublicWithoutJwt() {
        GatewayJwtAuthenticationFilter filter = createFilter(permissionClient(true));

        AtomicReference<ServerWebExchange> listExchange = new AtomicReference<>();
        MockServerWebExchange listRequest = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/catalogue/listings")
                        .header("X-User-Id", "spoofed-user")
        );
        filter.filter(listRequest, captureChain(listExchange)).block();

        assertEquals("/api/v1/catalogue/listings", listExchange.get().getRequest().getURI().getPath());
        assertNull(listExchange.get().getRequest().getHeaders().getFirst("X-User-Id"));
        assertNull(listRequest.getResponse().getStatusCode());

        AtomicReference<ServerWebExchange> detailExchange = new AtomicReference<>();
        MockServerWebExchange detailRequest = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/catalogue/listings/listing-1")
        );
        filter.filter(detailRequest, captureChain(detailExchange)).block();

        assertEquals("/api/v1/catalogue/listings/listing-1", detailExchange.get().getRequest().getURI().getPath());
        assertNull(detailRequest.getResponse().getStatusCode());

        AtomicReference<ServerWebExchange> summaryExchange = new AtomicReference<>();
        MockServerWebExchange summaryRequest = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/catalogue/listings/listing-1/summary")
        );
        filter.filter(summaryRequest, captureChain(summaryExchange)).block();

        assertEquals("/api/v1/catalogue/listings/listing-1/summary", summaryExchange.get().getRequest().getURI().getPath());
        assertNull(summaryRequest.getResponse().getStatusCode());
    }

    @Test
    void auctionReadRoutesShouldBePublicWithoutJwt() {
        GatewayJwtAuthenticationFilter filter = createFilter(permissionClient(true));
        AtomicReference<ServerWebExchange> listExchange = new AtomicReference<>();
        MockServerWebExchange listRequest = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/listings")
                        .header("X-User-Id", "spoofed-user")
        );
        filter.filter(listRequest, captureChain(listExchange)).block();

        assertEquals("/api/v1/listings", listExchange.get().getRequest().getURI().getPath());
        assertNull(listExchange.get().getRequest().getHeaders().getFirst("X-User-Id"));
        assertNull(listRequest.getResponse().getStatusCode());

        AtomicReference<ServerWebExchange> detailExchange = new AtomicReference<>();
        MockServerWebExchange detailRequest = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/listings/auction-1")
        );
        filter.filter(detailRequest, captureChain(detailExchange)).block();

        assertEquals("/api/v1/listings/auction-1", detailExchange.get().getRequest().getURI().getPath());
        assertNull(detailRequest.getResponse().getStatusCode());
    }

    private static GatewayMetrics gatewayMetrics() {
        return new GatewayMetrics(new SimpleMeterRegistry());
    }

    private GatewayJwtAuthenticationFilter createFilter(AuthPermissionClient authPermissionClient) {
        return new GatewayJwtAuthenticationFilter(
                authPermissionClient,
                ROUTE_PERMISSION_POLICY,
                gatewayMetrics(),
                SECRET,
                INTERNAL_TOKEN
        );
    }

    private AuthPermissionClient permissionClient(boolean allowed) {
        return (email, permission) -> Mono.just(allowed);
    }

    private AuthPermissionClient selectivePermissionClient() {
        return (email, permission) -> {
            if ("admin:*".equals(permission)) {
                return Mono.just("admin@bidmart.com".equals(email));
            }
            return Mono.just(false);
        };
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
