package id.ac.ui.cs.advprog.bidmartgateway;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdWorkflowContractTest {

    @Test
    void repositoryUsesPlatformManagedDeploymentInsteadOfCdWorkflow() {
        Path cdWorkflow = Path.of(".github/workflows/cd.yml");
        String ciWorkflow = ContractFileReader.read(".github/workflows/ci.yml");

        assertFalse(Files.exists(cdWorkflow));
        assertTrue(ciWorkflow.contains("cargo test") || ciWorkflow.contains("gradlew test"));
    }
}
