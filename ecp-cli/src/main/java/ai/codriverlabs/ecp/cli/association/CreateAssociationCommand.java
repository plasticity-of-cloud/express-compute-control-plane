package ai.codriverlabs.ecp.cli.association;

import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "pod-identity-association", description = "Create a workload identity")
public class CreateAssociationCommand implements Runnable {

    @Inject EcpApiClient apiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @Option(names = "--cluster-name", required = true) String clusterName;
    @Option(names = "--namespace", required = true) String namespace;
    @Option(names = "--service-account", required = true) String serviceAccount;
    @Option(names = "--role-arn", required = true) String roleArn;

    @Override
    public void run() {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("namespace", namespace);
            body.put("serviceAccount", serviceAccount);
            body.put("roleArn", roleArn);

            String response = apiClient.post(
                "/clusters/" + clusterName + "/workload-identities",
                body.toString());

            JsonNode node = mapper.readTree(response);
            String associationId = node.has("associationId") ? node.get("associationId").asText() : "-";

            System.out.printf("✓ Association created: %s/%s → %s%n", namespace, serviceAccount, roleArn);
            System.out.printf("  Association ID: %s%n", associationId);

            // Trust policy status
            String trustStatus = node.has("trustPolicyStatus") ? node.get("trustPolicyStatus").asText() : null;
            if ("APPLIED".equals(trustStatus) || "ALREADY_PRESENT".equals(trustStatus)) {
                System.out.printf("✓ Trust policy %s on role%n",
                    "APPLIED".equals(trustStatus) ? "updated" : "already configured");
            } else if ("MANUAL_ACTION_REQUIRED".equals(trustStatus)) {
                System.err.println("⚠ Trust policy NOT updated (role missing tag \"ecp-managed=true\")");
                System.err.println();
                System.err.println("  Add this statement to the role's trust policy:");
                if (node.has("requiredTrustPolicyStatement"))
                    System.err.println("  " + node.get("requiredTrustPolicyStatement").asText()
                        .replace("\n", "\n  "));
            }
        } catch (Exception e) {
            System.err.printf("Failed to create association: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
