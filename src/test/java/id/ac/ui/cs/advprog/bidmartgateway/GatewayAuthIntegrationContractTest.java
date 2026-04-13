package id.ac.ui.cs.advprog.bidmartgateway;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayAuthIntegrationContractTest {

    @Test
    void gatewayConfigShouldSupportFrontendAuthTraffic() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertTrue(yaml.contains("Path=/api/v1/auth/**"));
        assertTrue(yaml.contains("allowCredentials: true"));
        assertTrue(yaml.contains("exposedHeaders:"));
        assertTrue(yaml.contains("Authorization"));
    }
}
