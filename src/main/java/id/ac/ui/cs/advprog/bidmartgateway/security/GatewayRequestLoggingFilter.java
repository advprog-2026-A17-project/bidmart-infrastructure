package id.ac.ui.cs.advprog.bidmartgateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewayRequestLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayRequestLoggingFilter.class);
    private static final String HEADER_CORRELATION_ID = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startedAt = System.nanoTime();
        return chain.filter(exchange)
                .doFinally(signalType -> {
                    String correlationId = exchange.getRequest().getHeaders().getFirst(HEADER_CORRELATION_ID);
                    String method = exchange.getRequest().getMethod().name();
                    String path = exchange.getRequest().getURI().getPath();
                    HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
                    int status = statusCode == null ? 200 : statusCode.value();
                    long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

                    LOGGER.info(
                            "{{\"correlationId\":\"{}\",\"method\":\"{}\",\"path\":\"{}\",\"status\":{},\"durationMs\":{}}}",
                            correlationId,
                            method,
                            path,
                            status,
                            durationMs
                    );
                });
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
