package id.ac.ui.cs.advprog.bidmartgateway.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayMetricsTest {

    @Test
    void recordsRejectionAndRateLimitCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetrics metrics = new GatewayMetrics(registry);

        metrics.recordUnauthorized();
        metrics.recordForbidden();
        metrics.recordRateLimited();
        metrics.recordUnauthorized();

        assertEquals(2.0, registry.get("bidmart_gateway_rejections_total")
                .tag("reason", "unauthorized")
                .counter()
                .count());
        assertEquals(1.0, registry.get("bidmart_gateway_rejections_total")
                .tag("reason", "forbidden")
                .counter()
                .count());
        assertEquals(1.0, registry.get("bidmart_gateway_rate_limited_total").counter().count());
    }
}
