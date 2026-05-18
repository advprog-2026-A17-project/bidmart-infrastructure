package id.ac.ui.cs.advprog.bidmartgateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class GatewayRateLimitFilter implements GlobalFilter, Ordered {

    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxAttempts;
    private final long windowSeconds;

    @Autowired
    public GatewayRateLimitFilter(
            @Value("${app.gateway.rate-limit.max-attempts:20}") int maxAttempts,
            @Value("${app.gateway.rate-limit.window-seconds:60}") long windowSeconds
    ) {
        this(maxAttempts, windowSeconds, Clock.systemUTC());
    }

    GatewayRateLimitFilter(int maxAttempts, long windowSeconds, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String bucket = bucketFor(exchange);
        if (bucket == null) {
            return chain.filter(exchange);
        }

        String remoteAddress = exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        String key = bucket + ":" + remoteAddress;
        Instant now = clock.instant();
        Window window = windows.compute(key, (ignored, existing) -> {
            if (existing == null || !existing.expiresAt().isAfter(now)) {
                return new Window(1, now.plusSeconds(windowSeconds));
            }
            return new Window(existing.count() + 1, existing.expiresAt());
        });

        if (window.count() > maxAttempts) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -200;
    }

    private String bucketFor(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        String path = exchange.getRequest().getURI().getPath().toLowerCase(Locale.ROOT);
        if (HttpMethod.POST.equals(method) && path.equals("/api/v1/auth/login")) {
            return "login";
        }
        if (HttpMethod.POST.equals(method) && (path.equals("/api/v1/auctions") || path.equals("/api/v1/listings"))) {
            return "auction-create";
        }
        if (HttpMethod.POST.equals(method)
                && (path.startsWith("/api/v1/auctions/") || path.startsWith("/api/v1/listings/"))
                && path.endsWith("/bids")) {
            return "bid-place";
        }
        if (!HttpMethod.GET.equals(method) && (path.equals("/api/v1/wallet") || path.startsWith("/api/v1/wallet/"))) {
            return "wallet-mutate";
        }
        return null;
    }

    private record Window(int count, Instant expiresAt) {
    }
}
