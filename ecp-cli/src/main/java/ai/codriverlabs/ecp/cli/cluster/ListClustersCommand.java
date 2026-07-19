package ai.codriverlabs.ecp.cli.cluster;

import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

@Command(name = "clusters", description = "List registered clusters")
public class ListClustersCommand implements Runnable {

    @Inject EcpApiClient apiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void run() {
        try {
            String response = apiClient.get("/clusters");
            JsonNode root = mapper.readTree(response);
            JsonNode clusters = root.get("clusters");

            if (clusters == null || clusters.isEmpty()) {
                System.out.println("No clusters registered.");
                return;
            }

            System.out.printf("%-30s %-50s %-25s%n", "NAME", "ISSUER", "CREATED");
            for (JsonNode c : clusters) {
                System.out.printf("%-30s %-50s %-25s%n",
                    field(c, "clusterName"),
                    field(c, "issuer"),
                    field(c, "createdAt"));
            }
        } catch (Exception e) {
            System.err.printf("Failed to list clusters: %s%n", e.getMessage());
            System.exit(1);
        }
    }

    private String field(JsonNode node, String name) {
        JsonNode f = node.get(name);
        return f != null ? f.asText() : "-";
    }
}
