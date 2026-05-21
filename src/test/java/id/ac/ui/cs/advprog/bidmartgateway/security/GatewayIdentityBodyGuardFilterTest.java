package id.ac.ui.cs.advprog.bidmartgateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayIdentityBodyGuardFilterTest {

    private final GatewayIdentityBodyGuardFilter filter = new GatewayIdentityBodyGuardFilter();
    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    @Test
    void getRequestsBypassBodyGuard() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/catalogue/listings/search")
        );
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.filter(exchange, chain(chained)).block();

        assertTrue(chained.get());
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void mutationWithoutTrustedUserIdPassesThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/catalogue/listings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json("{\"title\":\"x\"}"))
        );
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.filter(exchange, chain(chained)).block();

        assertTrue(chained.get());
    }

    @Test
    void conflictingSellerIdReturns409() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/catalogue/listings")
                        .header("X-User-Id", "trusted-seller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json("{\"sellerId\":\"other\",\"title\":\"x\"}"))
        );

        filter.filter(exchange, noOp()).block();

        assertEquals(HttpStatus.CONFLICT, exchange.getResponse().getStatusCode());
    }

    @Test
    void matchingBodyRebuildsRequestBody() {
        String json = "{\"sellerId\":\"trusted-seller\",\"title\":\"x\"}";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/catalogue/listings")
                        .header("X-User-Id", "trusted-seller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json(json))
        );
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.filter(exchange, chain(chained)).block();

        assertTrue(chained.get());
        assertNull(exchange.getResponse().getStatusCode());
    }

    private Flux<org.springframework.core.io.buffer.DataBuffer> json(String body) {
        return Flux.just(bufferFactory.wrap(body.getBytes(StandardCharsets.UTF_8)));
    }

    private GatewayFilterChain chain(AtomicBoolean chained) {
        return exchange -> {
            chained.set(true);
            return Mono.empty();
        };
    }

    private GatewayFilterChain noOp() {
        return exchange -> Mono.empty();
    }
}
