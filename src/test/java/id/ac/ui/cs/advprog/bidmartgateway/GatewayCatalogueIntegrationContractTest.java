package id.ac.ui.cs.advprog.bidmartgateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayCatalogueIntegrationContractTest {

    @Test
    void gatewayConfigShouldSupportCatalogueListingTraffic() {
        String yaml = ContractFileReader.read("src/main/resources/application.yml");

        assertTrue(yaml.contains("- id: catalogue-service"));
        assertTrue(yaml.contains("uri: ${CATALOGUE_SERVICE_URL:http://catalogue-service:8081}"));
        assertTrue(yaml.contains("Path=/api/v1/catalogue/**"));
    }
}
