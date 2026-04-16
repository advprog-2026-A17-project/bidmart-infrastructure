package id.ac.ui.cs.advprog.bidmartgateway;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CdWorkflowContractTest {

    @Test
    void cdWorkflowShouldDeployToStagingHerokuApp() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/cd.yml"));

        assertTrue(workflow.contains("branches:"));
        assertTrue(workflow.contains("- staging"));
        assertTrue(workflow.contains("heroku_app_name: ${{ secrets.HEROKU_APP_NAME_STAGING }}"));
    }
}
