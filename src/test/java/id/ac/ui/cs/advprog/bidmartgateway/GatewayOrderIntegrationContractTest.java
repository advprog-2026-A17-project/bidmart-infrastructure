package id.ac.ui.cs.advprog.bidmartgateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayOrderIntegrationContractTest {

    @Test
    void gatewayConfigShouldSupportFutureOrderServiceTraffic() {
        String yaml = ContractFileReader.read("src/main/resources/application.yml");

        assertTrue(yaml.contains("- id: order-service"));
        assertTrue(yaml.contains("uri: ${ORDER_SERVICE_URL:http://localhost:8084}"));
        assertTrue(yaml.contains("Path=/api/v1/orders/**"));
    }

    @Test
    void gatewayConfigShouldSupportNotificationApiTraffic() {
        String yaml = ContractFileReader.read("src/main/resources/application.yml");

        assertTrue(yaml.contains("/api/v1/notifications/**"));
    }
}
