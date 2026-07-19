package ai.codriverlabs.ecp.cli.cluster;

import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "cluster", description = "Describe a cluster")
public class DescribeClusterCommand implements Runnable {

    @Inject EcpApiClient apiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @Parameters(index = "0", description = "Cluster name") String name;

    @Override
    public void run() {
        try {
            String response = apiClient.get("/clusters/" + name);
            JsonNode node = mapper.readTree(response);

            System.out.printf("Name:       %s%n", field(node, "clusterName"));
            System.out.printf("Issuer:     %s%n", field(node, "issuer"));
            System.out.printf("Created:    %s%n", field(node, "createdAt"));
            System.out.printf("Updated:    %s%n", field(node, "updatedAt"));
        } catch (Exception e) {
            System.err.printf("Failed to describe cluster: %s%n", e.getMessage());
            System.exit(1);
        }
    }

    private String field(JsonNode node, String name) {
        JsonNode f = node.get(name);
        return f != null ? f.asText() : "-";
    }
}
