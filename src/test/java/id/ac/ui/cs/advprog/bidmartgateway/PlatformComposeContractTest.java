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
        assertTrue(compose.contains("postgresql://postgres:postgres@auction-db:5432/bidmart_auction"));
        assertTrue(compose.contains("postgresql://postgres:postgres@wallet-db:5432/bidmart_wallet_db"));
        assertTrue(compose.contains("wallet-rust-data:"));

        assertTrue(compose.contains("MIDTRANS_SANDBOX_BASE_URL"));
        assertTrue(compose.contains("MIDTRANS_SERVER_KEY"));
        assertTrue(compose.contains("MIDTRANS_CLIENT_KEY"));

        assertTrue(gatewayConfig.contains("- id: notification-service-ws"));
        assertTrue(gatewayConfig.contains("uri: ${GATEWAY_ORDER_WS_SERVICE_URL:lb://order-notification-service}"));
        assertTrue(gatewayConfig.contains("uri: ${GATEWAY_ORDER_SERVICE_URL:lb://order-notification-service}"));
        assertTrue(gatewayConfig.contains("Path=/ws/notifications/**"));
        assertTrue(gatewayConfig.contains("spring-cloud-starter-loadbalancer") || ContractFileReader.read("build.gradle.kts").contains("spring-cloud-starter-loadbalancer"));
        assertTrue(compose.contains("GATEWAY_AUTH_SERVICE_URL: ${GATEWAY_AUTH_SERVICE_URL:-lb://auth-service}"));
        assertTrue(compose.contains("GATEWAY_ORDER_SERVICE_URL: ${GATEWAY_ORDER_SERVICE_URL:-lb://order-notification-service}"));
        assertTrue(compose.contains("expose:"));
    }

    @Test
    void composeShouldLoadInfrastructureEnvAfterRepoSpecificFallbacks() {
        String compose = ContractFileReader.read("docker-compose.yml");

        assertTrue(compose.contains("x-env-files:"));
        assertRepoEnvFallsBackToInfrastructureEnv(compose, "../bidmart-auth-service/.env");
        assertRepoEnvFallsBackToInfrastructureEnv(compose, "../bidmart-catalogue-service/.env");
        assertRepoEnvFallsBackToInfrastructureEnv(compose, "../bidmart-auction-service-rust/.env");
        assertRepoEnvFallsBackToInfrastructureEnv(compose, "../bidmart-wallet-service-rust/.env");
        assertRepoEnvFallsBackToInfrastructureEnv(compose, "../bidmart-order-and-notification-service/.env");
        assertRepoEnvFallsBackToInfrastructureEnv(compose, "../bidmart-frontend/.env");
    }

    private static void assertRepoEnvFallsBackToInfrastructureEnv(String compose, String repoEnvPath) {
        int repoEnvIndex = compose.indexOf("- path: " + repoEnvPath);
        int infrastructureEnvIndex = compose.indexOf("- path: .env", repoEnvIndex);

        assertTrue(repoEnvIndex >= 0, "Missing repo-specific fallback env: " + repoEnvPath);
        assertTrue(
                infrastructureEnvIndex > repoEnvIndex,
                "Infrastructure .env must be listed after " + repoEnvPath + " so it has precedence"
        );
    }
}
