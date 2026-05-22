package id.ac.ui.cs.advprog.bidmartgateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayRequestLoggingFilterTest {

    private final GatewayRequestLoggingFilter filter = new GatewayRequestLoggingFilter();

    @Test
    void filterInvokesChainAndCompletes() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/catalogue/listings")
                        .header("X-Correlation-Id", "corr-1")
        );
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.filter(exchange, chain(chained)).block();

        assertTrue(chained.get());
    }

    private GatewayFilterChain chain(AtomicBoolean chained) {
        return ex -> {
            chained.set(true);
            return Mono.empty();
        };
    }
}
