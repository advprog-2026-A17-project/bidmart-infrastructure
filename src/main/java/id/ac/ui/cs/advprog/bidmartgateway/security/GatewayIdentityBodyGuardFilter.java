package id.ac.ui.cs.advprog.bidmartgateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public class GatewayIdentityBodyGuardFilter implements GlobalFilter, Ordered {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final Set<HttpMethod> MUTATION_METHODS = Set.of(
            HttpMethod.POST,
            HttpMethod.PUT,
            HttpMethod.PATCH
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        HttpMethod method = request.getMethod();
        String path = request.getURI().getPath();

        if (method == null || !MUTATION_METHODS.contains(method) || !requiresBodyIdentityGuard(path)) {
            return chain.filter(exchange);
        }

        String trustedUserId = request.getHeaders().getFirst(HEADER_USER_ID);
        if (trustedUserId == null || trustedUserId.isBlank()) {
            return chain.filter(exchange);
        }

        return DataBufferUtils.join(request.getBody())
                .flatMap(dataBuffer -> {
                    byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bodyBytes);
                    DataBufferUtils.release(dataBuffer);

                    return IdentityBodyConflictChecker.findConflict(trustedUserId, bodyBytes)
                            .map(conflictingField -> reject(exchange, conflictingField))
                            .orElseGet(() -> chain.filter(rebuildExchange(exchange, bodyBytes)));
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        return -99;
    }

    private boolean requiresBodyIdentityGuard(String path) {
        return path.startsWith("/api/v1/catalogue/")
                || path.startsWith("/api/v1/wallet/")
                || path.startsWith("/api/v1/listings");
    }

    private Mono<Void> reject(ServerWebExchange exchange, String conflictingField) {
        exchange.getResponse().setStatusCode(HttpStatus.CONFLICT);
        byte[] payload = ("{\"message\":\"Identity field '" + conflictingField
                + "' conflicts with authenticated user\"}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(payload);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private ServerWebExchange rebuildExchange(ServerWebExchange exchange, byte[] bodyBytes) {
        ServerHttpRequest decorated = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                if (bodyBytes.length == 0) {
                    return Flux.empty();
                }
                DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bodyBytes);
                return Flux.just(buffer);
            }
        };
        return exchange.mutate().request(decorated).build();
    }
}
