package id.ac.ui.cs.advprog.bidmartgateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerComposeContractTest {

    @Test
    void dockerComposeShouldLiveInInfrastructureRepoAndBuildAllServices() {
        String compose = ContractFileReader.read("docker-compose.yml");

        assertTrue(compose.contains("services:"));
        assertTrue(compose.contains("gateway:"));
        assertTrue(compose.contains("context: ."));
        assertTrue(compose.contains("context: ../bidmart-auth-service"));
        assertTrue(compose.contains("context: ../bidmart-catalogue-service"));
        assertTrue(compose.contains("context: ../bidmart-auction-service-rust"));
        assertTrue(compose.contains("context: ../bidmart-wallet-service"));
        assertTrue(compose.contains("context: ../bidmart-frontend"));
    }

    @Test
    void dockerComposeShouldAllowEnvironmentSpecificOverrides() {
        String compose = ContractFileReader.read("docker-compose.yml");

        assertTrue(compose.contains("AUTH_SERVICE_URL: ${AUTH_SERVICE_URL:-http://auth-service:8080}"));
        assertTrue(compose.contains("CATALOGUE_SERVICE_URL: ${CATALOGUE_SERVICE_URL:-http://catalogue-service:8081}"));
        assertTrue(compose.contains("AUCTION_SERVICE_URL: ${AUCTION_SERVICE_URL:-http://auction-service:8082}"));
        assertTrue(compose.contains("WALLET_SERVICE_URL: ${WALLET_SERVICE_URL:-http://wallet-service:8083}"));
        assertTrue(compose.contains("SPRING_DATASOURCE_URL: ${AUTH_DATABASE_URL:-jdbc:postgresql://auth-db:5432/bidmart_auth}"));
        assertTrue(compose.contains("SPRING_DATASOURCE_URL: ${CATALOGUE_DATABASE_URL:-jdbc:postgresql://catalogue-db:5432/bidmart_catalogue}"));
        assertTrue(compose.contains("SPRING_DATASOURCE_URL: ${WALLET_DATABASE_URL:-jdbc:postgresql://wallet-db:5432/bidmart_wallet_db}"));
        assertTrue(compose.contains("DATABASE_URL: ${AUCTION_DATABASE_URL:-sqlite:///data/bidmart-auction.db}"));
    }
}
