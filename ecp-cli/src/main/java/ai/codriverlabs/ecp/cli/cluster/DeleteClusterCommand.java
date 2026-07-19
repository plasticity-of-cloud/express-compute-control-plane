package ai.codriverlabs.ecp.cli.cluster;

import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "cluster", description = "Delete a cluster")
public class DeleteClusterCommand implements Runnable {

    @Inject EcpApiClient apiClient;

    @Parameters(index = "0", description = "Cluster name") String name;

    @Override
    public void run() {
        try {
            apiClient.delete("/clusters/" + name);
            System.out.printf("✓ Cluster \"%s\" deregistered%n", name);
        } catch (Exception e) {
            System.err.printf("Failed to delete cluster: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
