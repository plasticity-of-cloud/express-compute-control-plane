package ai.codriverlabs.ecp.cli.association;

import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "pod-identity-association", description = "Delete a workload identity")
public class DeleteAssociationCommand implements Runnable {

    @Inject EcpApiClient apiClient;

    @Option(names = "--cluster-name", required = true) String clusterName;
    @Option(names = "--association-id", required = true) String associationId;

    @Override
    public void run() {
        try {
            apiClient.delete(
                "/clusters/" + clusterName + "/workload-identities/" + associationId);
            System.out.printf("✓ Association \"%s\" deleted%n", associationId);
        } catch (Exception e) {
            System.err.printf("Failed to delete association: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
