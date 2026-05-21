package id.ac.ui.cs.advprog.bidmartgateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class GatewayMetrics {

    private final Counter unauthorizedTotal;
    private final Counter forbiddenTotal;
    private final Counter rateLimitedTotal;

    public GatewayMetrics(MeterRegistry registry) {
        unauthorizedTotal = Counter.builder("bidmart_gateway_rejections_total")
                .description("Gateway rejected requests")
                .tag("reason", "unauthorized")
                .register(registry);
        forbiddenTotal = Counter.builder("bidmart_gateway_rejections_total")
                .description("Gateway rejected requests")
                .tag("reason", "forbidden")
                .register(registry);
        rateLimitedTotal = Counter.builder("bidmart_gateway_rate_limited_total")
                .description("Gateway rate-limited requests")
                .register(registry);
    }

    public void recordUnauthorized() {
        unauthorizedTotal.increment();
    }

    public void recordForbidden() {
        forbiddenTotal.increment();
    }

    public void recordRateLimited() {
        rateLimitedTotal.increment();
    }
}
