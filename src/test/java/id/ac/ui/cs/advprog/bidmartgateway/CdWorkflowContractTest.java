package id.ac.ui.cs.advprog.bidmartgateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CdWorkflowContractTest {

    @Test
    void cdWorkflowShouldDeployToStagingHerokuApp() {
        String workflow = ContractFileReader.read(".github/workflows/cd.yml");

        assertTrue(workflow.contains("branches:"));
        assertTrue(workflow.contains("- staging"));
        assertTrue(workflow.contains("heroku_app_name: ${{ secrets.HEROKU_APP_NAME_STAGING }}"));
    }
}
