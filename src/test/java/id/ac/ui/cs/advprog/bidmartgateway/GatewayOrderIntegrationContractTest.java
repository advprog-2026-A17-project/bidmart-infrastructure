package id.ac.ui.cs.advprog.bidmartgateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayOrderIntegrationContractTest {

    @Test
    void gatewayConfigShouldSupportFutureOrderServiceTraffic() {
        String yaml = ContractFileReader.read("src/main/resources/application.yml");

        assertTrue(yaml.contains("- id: order-service"));
        assertTrue(yaml.contains("uri: ${GATEWAY_ORDER_SERVICE_URL:lb://order-notification-service}"));
        assertTrue(yaml.contains("ORDER_SERVICE_INSTANCE_0:http://order-notification-service:8084"));
        assertTrue(yaml.contains("Path=/api/v1/orders,/api/v1/orders/**"));
    }

    @Test
    void gatewayConfigShouldSupportNotificationApiTraffic() {
        String yaml = ContractFileReader.read("src/main/resources/application.yml");

        assertTrue(yaml.contains("/api/v1/notifications/**"));
    }
}
