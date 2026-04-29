package id.ac.ui.cs.advprog.bidmartgateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
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

@Component
public class GatewayJwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USER_EMAIL = "X-User-Email";
    private static final String HEADER_USER_ROLES = "X-User-Roles";

    private final AuthPermissionClient authPermissionClient;
    private final SecretKey signingKey;

    public GatewayJwtAuthenticationFilter(
            AuthPermissionClient authPermissionClient,
            @Value("${app.auth.jwt.secret:bidmart-auth-secret-key-bidmart-auth-secret-key}") String jwtSecret
    ) {
        this.authPermissionClient = authPermissionClient;
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (isPublicRoute(request.getMethod(), path)) {
            return chain.filter(stripIdentityHeaders(exchange));
        }

        Claims claims = parseAccessClaims(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (claims == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }

        String email = claims.get("email", String.class);
        if (email == null || email.isBlank()) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }

        String requiredPermission = requiredPermission(request.getMethod(), path);
        if (requiredPermission == null) {
            return chain.filter(withVerifiedIdentity(exchange, claims));
        }

        return authPermissionClient.hasPermission(email, requiredPermission)
                .flatMap(allowed -> {
                    if (!allowed) {
                        return reject(exchange, HttpStatus.FORBIDDEN);
                    }
                    return chain.filter(withVerifiedIdentity(exchange, claims));
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
                    headers.set(HEADER_USER_ID, claims.getSubject());
                    headers.set(HEADER_USER_EMAIL, claims.get("email", String.class));
                    headers.set(HEADER_USER_ROLES, rolesHeader(claims));
                })
                .build();
        return exchange.mutate().request(request).build();
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
        return HttpMethod.OPTIONS.equals(method) || path.startsWith("/api/v1/auth/");
    }

    private String requiredPermission(HttpMethod method, String path) {
        if (HttpMethod.POST.equals(method) && matchesExact(path, "/api/v1/auctions")) {
            return "auction:create";
        }
        if (HttpMethod.POST.equals(method) && matchesChildAction(path, "/api/v1/auctions", "bids")) {
            return "bid:place";
        }
        if (HttpMethod.POST.equals(method) && matchesChildAction(path, "/api/v1/auctions", "close")) {
            return "auction:close";
        }
        if (HttpMethod.POST.equals(method) && matchesExact(path, "/api/v1/catalogue/listings")) {
            return "listing:create";
        }
        if (path.startsWith("/api/v1/wallet/") || matchesExact(path, "/api/v1/wallet")) {
            return HttpMethod.GET.equals(method) ? "wallet:view" : "wallet:mutate";
        }
        return null;
    }

    private boolean matchesExact(String actualPath, String expectedPath) {
        return actualPath.equals(expectedPath) || actualPath.equals(expectedPath + "/");
    }

    private boolean matchesChildAction(String path, String prefix, String action) {
        String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return normalized.startsWith(prefix + "/") && normalized.endsWith("/" + action);
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}
