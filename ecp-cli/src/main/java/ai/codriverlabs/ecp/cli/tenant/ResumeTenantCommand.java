package ai.codriverlabs.ecp.cli.tenant;

import ai.codriverlabs.ecp.cli.config.EcpConfig;
import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "tenant", description = "Resume a stopped managed cluster")
public class ResumeTenantCommand implements Runnable {

    @Inject EcpApiClient apiClient;

    @Parameters(index = "0", description = "Cluster name") String clusterName;

    @Option(names = "--wait", description = "Wait until cluster is reachable", defaultValue = "true")
    boolean wait;

    @Override
    public void run() {
        try {
            EcpConfig config = new EcpConfig();
            String provisioningUrl = config.getProvisioningUrl();
            if (provisioningUrl == null) {
                System.err.println("Error: provisioning URL not configured. Set ECP_PROVISIONING_URL or run 'ecp configure'.");
                System.exit(1);
            }
            int status = apiClient.postStatusOnUrl(provisioningUrl, "/clusters/" + clusterName + "/resume", "", "lambda");
            if (status == 404) {
                System.err.printf("Cluster \"%s\" not found%n", clusterName);
                System.exit(1);
            }
            if (status != 202 && status != 200) {
                System.err.printf("Unexpected status %d resuming cluster%n", status);
                System.exit(1);
            }
            System.out.printf("Resuming \"%s\"...%n", clusterName);

            if (!wait) return;

            // Poll GET /clusters/{name} until state is "ready" or "running"
            long deadline = System.currentTimeMillis() + 300_000;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(5_000);
                String body = apiClient.getBodyOnUrl(provisioningUrl, "/clusters/" + clusterName, "lambda");
                if (body != null && (body.contains("\"ready\"") || body.contains("\"running\""))) {
                    System.out.printf("✓ Cluster \"%s\" running%n", clusterName);
                    return;
                }
            }
            System.err.println("Timeout waiting for cluster to resume.");
            System.exit(1);
        } catch (Exception e) {
            System.err.printf("Failed to resume cluster: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
