package ai.codriverlabs.ecp.cli.cluster;

import ai.codriverlabs.ecp.cli.config.EcpConfig;
import ai.codriverlabs.ecp.cli.util.AwsSigV4Signer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Reusable helper that fetches cluster access details and saves the SSH key locally.
 * Equivalent to running {@code ecp get-cluster-access --save-key <clusterName>}.
 *
 * <p>Used automatically by create-cluster (--wait) and resume-cluster after
 * the cluster reaches "ready" state.
 */
public final class ClusterAccessHelper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClusterAccessHelper() {}

    /**
     * Fetches cluster state, saves the SSH key from Secrets Manager, and prints connection info.
     *
     * @param clusterName the cluster name
     * @param region      resolved AWS region
     * @param config      EcpConfig instance
     * @param outputFormat "text" or "json"
     */
    public static void saveKeyAndPrintAccess(String clusterName, String region, EcpConfig config, String outputFormat) {
        try {
            String provisioningUrl = config.getProvisioningUrl();
            if (provisioningUrl == null) return;

            String url = provisioningUrl.replaceAll("/$", "") + "/clusters/" + clusterName;
            URI uri = URI.create(url);

            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri).GET();
            AwsSigV4Signer signer = AwsSigV4Signer.create(region);
            if (signer != null) signer.sign(builder, "GET", uri, null, "lambda");
            else builder.header("Content-Type", "application/json");

            HttpResponse<String> response = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) return;

            JsonNode cluster = MAPPER.readTree(response.body());

            String state = cluster.path("state").asText(null);
            if (!"ready".equals(state) && !"running".equals(state)) return;

            String publicIp = cluster.path("publicIp").asText(null);
            String tenantId = cluster.path("tenantId").asText(null);
            String sshKeySecretArn = cluster.path("sshKeySecretArn").asText(null);

            if (publicIp == null || publicIp.isBlank()) return;

            Path keyPath = tenantId != null ? config.tenantSshKeyPath(region, tenantId) : null;

            // Fetch and save SSH key from Secrets Manager
            if (sshKeySecretArn != null && !sshKeySecretArn.isBlank() && keyPath != null) {
                String privateKey = fetchSecretValue(sshKeySecretArn, region);
                if (privateKey != null) {
                    Files.createDirectories(keyPath.getParent());
                    Files.writeString(keyPath, privateKey);
                    try {
                        Files.setPosixFilePermissions(keyPath, PosixFilePermissions.fromString("rw-------"));
                    } catch (UnsupportedOperationException ignored) {}
                }
            }

            // Print connection info
            if ("text".equals(outputFormat)) {
                System.out.println();
                System.out.printf("  Cluster:    %s%n", clusterName);
                System.out.printf("  Public IP:  %s%n", publicIp);
                if (keyPath != null && Files.exists(keyPath)) {
                    System.out.printf("  SSH key:    %s%n", keyPath);
                    System.out.println();
                    System.out.println("  Connect:");
                    System.out.printf("    ssh -i %s ec2-user@%s%n", keyPath, publicIp);
                }
            }
        } catch (Exception e) {
            // Non-fatal — the cluster is already created/resumed, access info is a convenience
            System.err.println("  (could not fetch cluster access details: " + e.getMessage() + ")");
        }
    }

    private static String fetchSecretValue(String secretArn, String region) {
        try {
            String body = "{\"SecretId\":\"" + secretArn + "\"}";
            URI uri = URI.create("https://secretsmanager." + region + ".amazonaws.com/");

            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri);
            AwsSigV4Signer signer = AwsSigV4Signer.create(region);
            if (signer == null) return null;

            signer.sign(builder, "POST", uri, body, "secretsmanager",
                "application/x-amz-json-1.1",
                java.util.Map.of("X-Amz-Target", "secretsmanager.GetSecretValue"));
            builder.POST(HttpRequest.BodyPublishers.ofString(body));

            HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() >= 400) return null;

            JsonNode node = MAPPER.readTree(resp.body());
            return node.path("SecretString").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
