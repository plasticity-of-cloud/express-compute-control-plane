package ai.codriverlabs.ecp.cli.cluster;

import ai.codriverlabs.ecp.cli.config.EcpConfig;
import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Unified delete-cluster command. Server determines teardown scope:
 * - managed clusters → full teardown (EC2, IAM, network, secrets)
 * - self-managed clusters → remove DynamoDB record only
 */
@Command(name = "delete-cluster", description = "Delete a cluster (managed: full teardown; self-managed: deregister)")
public class UnifiedDeleteClusterCommand implements Runnable {

    @Parameters(index = "0", description = "Cluster name")
    String name;

    @Option(names = "--region", description = "AWS region")
    String region;

    @Option(names = "--output", defaultValue = "text", description = "Output format: text or json")
    String output;

    @Inject EcpApiClient apiClient;

    @Override
    public void run() {
        try {
            EcpConfig config = new EcpConfig();
            String resolvedRegion = region != null ? region : config.getRegion();

            String provisioningUrl = config.getProvisioningUrl();
            if (provisioningUrl == null) {
                System.err.println("Error: provisioning URL not configured. Set ECP_PROVISIONING_URL or run 'ecp configure'.");
                System.exit(1);
            }
            String url = provisioningUrl.replaceAll("/$", "") + "/clusters/" + name;
            apiClient.deleteFunctionUrl(url, resolvedRegion);

            if ("text".equals(output)) {
                System.out.printf("✓ Cluster \"%s\" deleted%n", name);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
