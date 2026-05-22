package id.ac.ui.cs.advprog.bidmartgateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import id.ac.ui.cs.advprog.bidmartgateway.metrics.GatewayMetrics;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class GatewayJwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USER_EMAIL = "X-User-Email";
    private static final String HEADER_USER_ROLES = "X-User-Roles";
    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Service-Token";
    private static final String HEADER_CORRELATION_ID = "X-Correlation-Id";

    private final AuthPermissionClient authPermissionClient;
    private final RoutePermissionPolicy routePermissionPolicy;
    private final GatewayMetrics gatewayMetrics;
    private final SecretKey signingKey;
    private final String internalServiceToken;

    public GatewayJwtAuthenticationFilter(
            AuthPermissionClient authPermissionClient,
            RoutePermissionPolicy routePermissionPolicy,
            GatewayMetrics gatewayMetrics,
            @Value("${app.auth.jwt.secret:bidmart-auth-secret-key-bidmart-auth-secret-key}") String jwtSecret,
            @Value("${app.gateway.internal-token:bidmart-local-internal-token}") String internalServiceToken
    ) {
        this.authPermissionClient = authPermissionClient;
        this.routePermissionPolicy = routePermissionPolicy;
        this.gatewayMetrics = gatewayMetrics;
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.internalServiceToken = internalServiceToken;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (path.startsWith("/actuator/")) {
            return chain.filter(stripIdentityHeaders(exchange));
        }

        if (hasValidInternalToken(request)) {
            return chain.filter(withInternalServiceToken(exchange));
        }

        if (isPublicRoute(request.getMethod(), path)) {
            return chain.filter(stripIdentityHeaders(exchange));
        }

        // Optionally-authenticated routes: allow through with or without token,
        // but forward identity if a valid token is present (e.g. GET catalogue)
        if (isOptionallyAuthenticatedRoute(request.getMethod(), path)) {
            Claims claims = parseAccessClaims(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            if (claims != null) {
                String email = claims.get("email", String.class);
                if (email != null && !email.isBlank()) {
                    return chain.filter(withVerifiedIdentity(exchange, claims));
                }
            }
            return chain.filter(stripIdentityHeaders(exchange));
        }

        Claims claims = parseAccessClaims(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (claims == null) {
            gatewayMetrics.recordUnauthorized();
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }

        String email = claims.get("email", String.class);
        if (email == null || email.isBlank()) {
            gatewayMetrics.recordUnauthorized();
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }

        String requiredPermission = routePermissionPolicy.requiredPermission(request.getMethod(), path);
        if (requiredPermission == null) {
            return chain.filter(withVerifiedIdentity(exchange, claims));
        }

        Mono<Boolean> permissionCheck = authPermissionClient.hasPermission(email, requiredPermission);

        return permissionCheck.flatMap(allowed -> {
                    if (allowed) {
                        return chain.filter(withVerifiedIdentity(exchange, claims));
                    }
                    if (!requiresElevatedPermission(requiredPermission)) {
                        gatewayMetrics.recordForbidden();
                        return reject(exchange, HttpStatus.FORBIDDEN);
                    }
                    return authPermissionClient.hasPermission(email, "admin:*")
                            .flatMap(escalated -> {
                                if (!escalated) {
                                    gatewayMetrics.recordForbidden();
                                    return reject(exchange, HttpStatus.FORBIDDEN);
                                }
                                return chain.filter(withVerifiedIdentity(exchange, claims));
                            });
                });
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private Claims parseAccessClaims(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(authorizationHeader.substring("Bearer ".length()))
                    .getPayload();
            if (!TOKEN_TYPE_ACCESS.equals(claims.get("type", String.class))) {
                return null;
            }
            return claims;
        } catch (JwtException | IllegalArgumentException exception) {
            return null;
        }
    }

    private ServerWebExchange stripIdentityHeaders(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USER_EMAIL);
                    headers.remove(HEADER_USER_ROLES);
                    headers.remove(HEADER_INTERNAL_TOKEN);
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private ServerWebExchange withInternalServiceToken(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USER_EMAIL);
                    headers.remove(HEADER_USER_ROLES);
                    headers.set(HEADER_INTERNAL_TOKEN, internalServiceToken);
                    headers.computeIfAbsent(HEADER_CORRELATION_ID, ignored -> List.of(UUID.randomUUID().toString()));
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private ServerWebExchange withVerifiedIdentity(ServerWebExchange exchange, Claims claims) {
        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USER_EMAIL);
                    headers.remove(HEADER_USER_ROLES);
                    headers.remove(HEADER_INTERNAL_TOKEN);
                    headers.set(HEADER_USER_ID, claims.getSubject());
                    headers.set(HEADER_USER_EMAIL, claims.get("email", String.class));
                    headers.set(HEADER_USER_ROLES, rolesHeader(claims));
                    headers.set(HEADER_INTERNAL_TOKEN, internalServiceToken);
                    headers.computeIfAbsent(HEADER_CORRELATION_ID, ignored -> List.of(UUID.randomUUID().toString()));
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private boolean hasValidInternalToken(ServerHttpRequest request) {
        String token = request.getHeaders().getFirst(HEADER_INTERNAL_TOKEN);
        return internalServiceToken != null
                && !internalServiceToken.isBlank()
                && internalServiceToken.equals(token);
    }

    private String rolesHeader(Claims claims) {
        Object roles = claims.get("roles");
        if (roles instanceof List<?> roleList) {
            return roleList.stream()
                    .map(Objects::toString)
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
        }
        return Objects.toString(roles, "");
    }

    private boolean isPublicRoute(HttpMethod method, String path) {
        return HttpMethod.OPTIONS.equals(method) || 
               path.startsWith("/api/v1/auth/") || 
               path.equals("/ws") || 
               path.startsWith("/ws/");
    }

    /**
     * Routes that do not require authentication but should forward identity
     * headers when a valid token is present. This allows public browsing
     * of catalogue and listing bid sessions while still providing user context.
     */
    private boolean isOptionallyAuthenticatedRoute(HttpMethod method, String path) {
        return isPublicCatalogueRead(method, path) || isPublicAuctionRead(method, path);
    }

    private boolean isPublicCatalogueRead(HttpMethod method, String path) {
        if (!HttpMethod.GET.equals(method)) {
            return false;
        }
        String normalized = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;
        return normalized.equals("/api/v1/catalogue/listings")
                || normalized.equals("/api/v1/catalogue/listings/search")
                || normalized.equals("/api/v1/catalogue/categories/tree")
                || normalized.matches("^/api/v1/catalogue/listings/[^/]+$")
                || normalized.matches("^/api/v1/catalogue/listings/[^/]+/summary$");
    }

    private boolean isPublicAuctionRead(HttpMethod method, String path) {
        if (!HttpMethod.GET.equals(method)) {
            return false;
        }
        String normalized = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;
        return normalized.equals("/api/v1/listings")
                || normalized.matches("^/api/v1/listings/[^/]+$")
                || normalized.matches("^/api/v1/listings/[^/]+/bids$");
    }

    private boolean requiresElevatedPermission(String requiredPermission) {
        return requiredPermission.startsWith("admin:")
                || "wallet:view".equals(requiredPermission)
                || "wallet:mutate".equals(requiredPermission);
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}
