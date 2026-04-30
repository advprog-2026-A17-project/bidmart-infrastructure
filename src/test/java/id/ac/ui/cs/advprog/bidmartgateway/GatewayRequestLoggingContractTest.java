package id.ac.ui.cs.advprog.bidmartgateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayRequestLoggingContractTest {

    @Test
    void gatewayShouldHaveStructuredRequestLoggingWithCorrelationId() {
        String source = ContractFileReader.read(
                "src/main/java/id/ac/ui/cs/advprog/bidmartgateway/security/GatewayRequestLoggingFilter.java"
        );

        assertTrue(source.contains("correlationId"));
        assertTrue(source.contains("method"));
        assertTrue(source.contains("path"));
        assertTrue(source.contains("status"));
        assertTrue(source.contains("durationMs"));
    }
}
