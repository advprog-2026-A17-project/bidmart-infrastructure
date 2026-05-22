package id.ac.ui.cs.advprog.bidmartgateway;

import id.ac.ui.cs.advprog.bidmartgateway.metrics.GatewayMetrics;
import id.ac.ui.cs.advprog.bidmartgateway.security.GatewayRateLimitFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayRateLimitFilterTest {

    private static GatewayMetrics gatewayMetrics() {
        return new GatewayMetrics(new SimpleMeterRegistry());
    }

    @Test
    void loginShouldBeRateLimitedAfterConfiguredAttemptLimit() {
        GatewayRateLimitFilter filter = new GatewayRateLimitFilter(gatewayMetrics(), 2, 60);
        GatewayFilterChain chain = exchange -> Mono.empty();

        filter.filter(exchange(HttpMethod.POST, "/api/v1/auth/login"), chain).block();
        filter.filter(exchange(HttpMethod.POST, "/api/v1/auth/login"), chain).block();
        MockServerWebExchange limited = exchange(HttpMethod.POST, "/api/v1/auth/login");

        filter.filter(limited, chain).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, limited.getResponse().getStatusCode());
    }

    @Test
    void protectedMutationRoutesShouldBeRateLimitedIndependently() {
        GatewayRateLimitFilter filter = new GatewayRateLimitFilter(gatewayMetrics(), 1, 60);
        GatewayFilterChain chain = exchange -> Mono.empty();

        filter.filter(exchange(HttpMethod.POST, "/api/v1/listings"), chain).block();
        MockServerWebExchange auctionLimited = exchange(HttpMethod.POST, "/api/v1/listings");
        filter.filter(auctionLimited, chain).block();

        filter.filter(exchange(HttpMethod.POST, "/api/v1/listings/auction-1/bids"), chain).block();
        MockServerWebExchange bidLimited = exchange(HttpMethod.POST, "/api/v1/listings/auction-1/bids");
        filter.filter(bidLimited, chain).block();

        filter.filter(exchange(HttpMethod.POST, "/api/v1/wallet/hold"), chain).block();
        MockServerWebExchange walletLimited = exchange(HttpMethod.POST, "/api/v1/wallet/hold");
        filter.filter(walletLimited, chain).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, auctionLimited.getResponse().getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, bidLimited.getResponse().getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, walletLimited.getResponse().getStatusCode());
    }

    private MockServerWebExchange exchange(HttpMethod method, String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(method, path));
    }
}
