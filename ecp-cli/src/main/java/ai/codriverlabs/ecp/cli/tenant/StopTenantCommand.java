package ai.codriverlabs.ecp.cli.tenant;

import ai.codriverlabs.ecp.cli.config.EcpConfig;
import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "tenant", description = "Stop a managed cluster (EC2 instance stopped, EBS preserved)")
public class StopTenantCommand implements Runnable {

    @Inject EcpApiClient apiClient;

    @Parameters(index = "0", description = "Cluster name") String clusterName;

    @Override
    public void run() {
        try {
            EcpConfig config = new EcpConfig();
            String provisioningUrl = config.getProvisioningUrl();
            if (provisioningUrl == null) {
                System.err.println("Error: provisioning URL not configured. Set ECP_PROVISIONING_URL or run 'ecp configure'.");
                System.exit(1);
            }
            int status = apiClient.postStatusOnUrl(provisioningUrl, "/clusters/" + clusterName + "/stop", "", "lambda");
            if (status == 404) {
                System.err.printf("Cluster \"%s\" not found%n", clusterName);
                System.exit(1);
            }
            if (status != 202 && status != 200) {
                System.err.printf("Unexpected status %d stopping cluster%n", status);
                System.exit(1);
            }
            System.out.printf("Stopping \"%s\" — resume with 'ecp resume-cluster %s'%n", clusterName, clusterName);
        } catch (Exception e) {
            System.err.printf("Failed to stop cluster: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
