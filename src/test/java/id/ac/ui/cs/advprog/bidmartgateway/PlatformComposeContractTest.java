package id.ac.ui.cs.advprog.bidmartgateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformComposeContractTest {

    @Test
    void composeShouldExposeRabbitPostgresRustServicesAndMidtransSandboxConfig() {
        String compose = ContractFileReader.read("docker-compose.yml");
        String gatewayConfig = ContractFileReader.read("src/main/resources/application.yml");

        assertTrue(compose.contains("rabbitmq:"));
        assertTrue(compose.contains("rabbitmq:3-management-alpine"));
        assertTrue(compose.contains("RABBITMQ_URL"));
        assertTrue(compose.contains("bidmart.events"));

        assertTrue(compose.contains("auction-db:"));
        assertTrue(compose.contains("sqlite:///data/bidmart-auction.db"));
        assertTrue(compose.contains("sqlite:///data/bidmart-wallet.db"));
        assertTrue(compose.contains("wallet-rust-data:"));

        assertTrue(compose.contains("CATALOGUE_AUCTION_EVENTS_QUEUE"));
        assertTrue(compose.contains("ORDER_AUCTION_EVENTS_QUEUE"));
        assertTrue(compose.contains("amqp://guest:guest@rabbitmq:5672/%2f"));

        assertTrue(compose.contains("MIDTRANS_SANDBOX_BASE_URL"));
        assertTrue(compose.contains("MIDTRANS_SERVER_KEY"));
        assertTrue(compose.contains("MIDTRANS_CLIENT_KEY"));

        assertTrue(gatewayConfig.contains("- id: notification-service-ws"));
        assertTrue(gatewayConfig.contains("uri: ${ORDER_SERVICE_URL:http://localhost:8084}"));
        assertTrue(gatewayConfig.contains("Path=/ws/notifications/**"));
    }
}
