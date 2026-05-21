package id.ac.ui.cs.advprog.bidmartgateway;

import id.ac.ui.cs.advprog.bidmartgateway.metrics.GatewayMetrics;
import id.ac.ui.cs.advprog.bidmartgateway.security.GatewayIdentityBodyGuardFilter;
import id.ac.ui.cs.advprog.bidmartgateway.security.GatewayJwtAuthenticationFilter;
import id.ac.ui.cs.advprog.bidmartgateway.security.RoutePermissionPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-style tests for gateway identity enforcement on mutation routes.
 */
class GatewayIdentityEnforcementIntegrationTest {

    private static final String SECRET = "bidmart-auth-secret-key-bidmart-auth-secret-key";
    private static final String INTERNAL_TOKEN = "internal-gateway-token";
    private static final RoutePermissionPolicy ROUTE_PERMISSION_POLICY = new RoutePermissionPolicy();
    private static final DefaultDataBufferFactory BUFFER_FACTORY = new DefaultDataBufferFactory();

    private final GatewayJwtAuthenticationFilter jwtFilter = new GatewayJwtAuthenticationFilter(
            (email, permission) -> Mono.just(true),
            ROUTE_PERMISSION_POLICY,
            new GatewayMetrics(new SimpleMeterRegistry()),
            SECRET,
            INTERNAL_TOKEN
    );
    private final GatewayIdentityBodyGuardFilter bodyGuardFilter = new GatewayIdentityBodyGuardFilter();

    @Test
    void postCatalogueListingWithoutJwtShouldBeUnauthorized() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/catalogue/listings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(jsonBody("{\"title\":\"Item\"}"))
        );

        jwtFilter.filter(exchange, noOpChain()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void putCatalogueListingShouldForwardTrustedUserId() {
        String token = accessToken("seller-trusted", "seller@test.com", List.of("SELLER"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.put("/api/v1/catalogue/listings/listing-1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-User-Id", "spoofed-seller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(jsonBody("{\"title\":\"Updated\"}"))
        );

        jwtFilter.filter(exchange, captureChain(forwarded)).block();

        assertEquals("seller-trusted", forwarded.get().getRequest().getHeaders().getFirst("X-User-Id"));
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void postWalletTopUpShouldRejectConflictingBodyUserId() {
        String token = accessToken("wallet-owner", "buyer@test.com", List.of("BUYER"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/wallet/wallet-owner/top-up")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(jsonBody("{\"userId\":\"other-user\",\"amountCents\":1000}"))
        );

        jwtFilter.filter(exchange, captureChain(forwarded)).block();
        bodyGuardFilter.filter(forwarded.get(), noOpChain()).block();

        assertEquals(HttpStatus.CONFLICT, forwarded.get().getResponse().getStatusCode());
    }

    @Test
    void postListingBidShouldAllowMatchingBidderId() {
        String token = accessToken("bidder-1", "bidder@test.com", List.of("BUYER"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/listings/auction-1/bids")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(jsonBody("{\"bidderId\":\"bidder-1\",\"amountCents\":5000}"))
        );

        jwtFilter.filter(exchange, captureChain(forwarded)).block();
        bodyGuardFilter.filter(forwarded.get(), noOpChain()).block();

        assertNull(forwarded.get().getResponse().getStatusCode());
        assertEquals("bidder-1", forwarded.get().getRequest().getHeaders().getFirst("X-User-Id"));
    }

    @Test
    void gatewayRoutesShouldDeclareMutationPathsForIdentityGuard() {
        String yaml = ContractFileReader.read("src/main/resources/application.yml");
        assertTrue(yaml.contains("Path=/api/v1/catalogue/**"));
        assertTrue(yaml.contains("Path=/api/v1/wallet/**"));
        assertTrue(yaml.contains("Path=/api/v1/listings,/api/v1/listings/**"));
    }

    private Flux<DataBuffer> jsonBody(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return Flux.just(BUFFER_FACTORY.wrap(bytes));
    }

    private GatewayFilterChain noOpChain() {
        return exchange -> Mono.empty();
    }

    private GatewayFilterChain captureChain(AtomicReference<ServerWebExchange> captured) {
        return exchange -> {
            captured.set(exchange);
            return Mono.empty();
        };
    }

    private String accessToken(String subject, String email, List<String> roles) {
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
