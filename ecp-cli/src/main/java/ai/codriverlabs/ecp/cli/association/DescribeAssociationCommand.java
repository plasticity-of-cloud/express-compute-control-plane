package ai.codriverlabs.ecp.cli.association;

import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "pod-identity-association", description = "Describe a workload identity")
public class DescribeAssociationCommand implements Runnable {

    @Inject EcpApiClient apiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @Option(names = "--cluster-name", required = true) String clusterName;
    @Option(names = "--association-id", required = true) String associationId;

    @Override
    public void run() {
        try {
            String response = apiClient.get(
                "/clusters/" + clusterName + "/workload-identities/" + associationId);
            JsonNode node = mapper.readTree(response);

            System.out.printf("Association ID:  %s%n", field(node, "associationId"));
            System.out.printf("Cluster:         %s%n", field(node, "clusterName"));
            System.out.printf("Namespace:       %s%n", field(node, "namespace"));
            System.out.printf("Service Account: %s%n", field(node, "serviceAccount"));
            System.out.printf("Role ARN:        %s%n", field(node, "roleArn"));
            System.out.printf("Created:         %s%n", field(node, "createdAt"));
        } catch (Exception e) {
            System.err.printf("Failed to describe association: %s%n", e.getMessage());
            System.exit(1);
        }
    }

    private String field(JsonNode node, String name) {
        JsonNode f = node.get(name);
        return f != null ? f.asText() : "-";
    }
}
