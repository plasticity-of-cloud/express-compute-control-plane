package ai.codriverlabs.ecp.cli.cluster;

import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import ai.codriverlabs.ecp.cli.util.KubeApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "cluster", description = "Update a cluster")
public class UpdateClusterCommand implements Runnable {

    @Inject EcpApiClient apiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    // package-private for testing
    KubeApiClient kubeApiClient;

    @Parameters(index = "0", description = "Cluster name") String name;
    @Option(names = "--refresh-jwks", description = "Re-read and push JWKS from cluster") boolean refreshJwks;
    @Option(names = "--kubeconfig", description = "Path to kubeconfig (default: ~/.kube/config)") String kubeconfig;

    @Override
    public void run() {
        if (!refreshJwks) {
            System.err.println("No update action specified. Use --refresh-jwks.");
            System.exit(1);
        }

        try {
            KubeApiClient kube = kubeApiClient != null ? kubeApiClient : new KubeApiClient(kubeconfig);
            String jwks = kube.get("/openid/v1/jwks");

            ObjectNode body = mapper.createObjectNode();
            body.put("jwks", jwks);

            apiClient.put("/clusters/" + name + "/jwks", body.toString());
            System.out.printf("✓ JWKS refreshed for \"%s\"%n", name);
        } catch (Exception e) {
            System.err.printf("Failed to update cluster: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
