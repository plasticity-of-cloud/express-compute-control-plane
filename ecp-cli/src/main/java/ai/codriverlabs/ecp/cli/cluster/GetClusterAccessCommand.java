package ai.codriverlabs.ecp.cli.cluster;

import ai.codriverlabs.ecp.cli.config.EcpConfig;
import ai.codriverlabs.ecp.cli.util.AwsSigV4Signer;
import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Retrieves the public IP and SSH connection details for a managed cluster on demand.
 *
 * <p>Data sources (no backend changes required):
 * <ul>
 *   <li>{@code publicIp} — read from TenantItem via GET /clusters/{name}</li>
 *   <li>SSH key — local .pem file written by create-cluster --wait, or re-fetched
 *       from Secrets Manager when {@code --save-key} / {@code --print-key} is used</li>
 * </ul>
 *
 * <p>Secrets Manager is called directly from the CLI using {@link AwsSigV4Signer}
 * (same HTTP+SigV4 pattern as SSM in EcpConfig) — no extra SDK dependency.
 */
@Command(name = "get-cluster-access", description = "Show SSH connection details for a managed cluster")
public class GetClusterAccessCommand implements Runnable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", description = "Cluster name")
    String name;

    @Option(names = "--print-key",
            description = "Re-fetch the SSH private key from Secrets Manager and print it to stdout")
    boolean printKey;

    @Option(names = "--save-key",
            description = "Re-fetch the SSH private key from Secrets Manager and save/overwrite the local .pem file")
    boolean saveKey;

    @Option(names = "--region", description = "AWS region (defaults to configured region)")
    String region;

    @Option(names = "--output", defaultValue = "text", description = "Output format: text or json")
    String output;

    @Inject EcpApiClient apiClient;

    @Override
    public void run() {
        try {
            EcpConfig config = new EcpConfig();
            String resolvedRegion = region != null ? region : config.getRegion();

            // 1. Fetch cluster state (GET /clusters/{name} on provisioning Function URL)
            String provisioningUrl = config.getProvisioningUrl();
            if (provisioningUrl == null) {
                System.err.println("Error: provisioning URL not configured. Run 'ecp configure'.");
                System.exit(1);
            }
            String url = provisioningUrl.replaceAll("/$", "") + "/clusters/" + name;
            URI uri = URI.create(url);

            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri).GET();
            AwsSigV4Signer signer = AwsSigV4Signer.create(resolvedRegion);
            if (signer != null) signer.sign(builder, "GET", uri, null, "lambda");
            else builder.header("Content-Type", "application/json");

            HttpResponse<String> response = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                System.err.printf("Error: cluster '%s' not found%n", name);
                System.exit(1);
            }
            if (response.statusCode() >= 400) {
                JsonNode err = MAPPER.readTree(response.body());
                System.err.println("Error: " + (err.has("message") ? err.get("message").asText() : response.body()));
                System.exit(1);
            }

            JsonNode cluster = MAPPER.readTree(response.body());

            // 2. Validate cluster is managed and ready
            boolean managed = cluster.path("managed").asBoolean(true);
            if (!managed) {
                System.err.printf("Error: cluster '%s' is self-managed and has no SSH access managed by this system%n", name);
                System.exit(1);
            }

            String state = cluster.path("state").asText(null);
            if ("stopped".equals(state) || "hibernating".equals(state)) {
                System.err.printf("Error: cluster '%s' is stopped. Resume it first: ecp resume-cluster %s%n", name, name);
                System.exit(1);
            }
            if (!"ready".equals(state)) {
                int progress = cluster.path("progress").asInt(0);
                String phase = cluster.path("phase").asText("unknown");
                System.err.printf("Error: cluster '%s' is not ready yet (state: %s, %d%%, phase: %s)%n",
                    name, state != null ? state : "unknown", progress, phase);
                System.exit(1);
            }

            String publicIp = cluster.path("publicIp").asText(null);
            if (publicIp == null || publicIp.isBlank()) {
                System.err.printf("Error: cluster '%s' has no public IP recorded — it may still be booting%n", name);
                System.exit(1);
            }

            String tenantId = cluster.path("tenantId").asText(null);
            Path keyPath = tenantId != null ? config.tenantSshKeyPath(resolvedRegion, tenantId) : null;

            // 3. Optionally re-fetch SSH key from Secrets Manager
            if (printKey || saveKey) {
                String sshKeySecretArn = cluster.path("sshKeySecretArn").asText(null);
                if (sshKeySecretArn == null || sshKeySecretArn.isBlank()) {
                    System.err.println("Error: no SSH key secret ARN found for this cluster");
                    System.exit(1);
                }
                String privateKey = fetchSecretValue(sshKeySecretArn, resolvedRegion);

                if (saveKey && keyPath != null) {
                    Files.createDirectories(keyPath.getParent());
                    Files.writeString(keyPath, privateKey);
                    try {
                        Files.setPosixFilePermissions(keyPath, PosixFilePermissions.fromString("rw-------"));
                    } catch (UnsupportedOperationException ignored) {}
                    if ("text".equals(output)) System.out.println("SSH key saved to: " + keyPath);
                }

                if (printKey) {
                    System.out.println(privateKey);
                    return;
                }
            }

            // 4. Output
            if ("json".equals(output)) {
                var result = new java.util.LinkedHashMap<String, Object>();
                result.put("clusterName", name);
                result.put("tenantId", tenantId);
                result.put("publicIp", publicIp);
                result.put("sshKeyPath", keyPath != null ? keyPath.toString() : null);
                result.put("sshCommand", sshCommand(publicIp, keyPath));
                System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            } else {
                System.out.printf("  Cluster:    %s%n", name);
                System.out.printf("  Public IP:  %s%n", publicIp);
                if (keyPath != null) {
                    System.out.printf("  SSH key:    %s%n", keyPath);
                    System.out.println();
                    System.out.println("  Connect:");
                    System.out.printf("  %s%n", sshCommand(publicIp, keyPath));
                }
                if (keyPath == null || !Files.exists(keyPath)) {
                    System.out.println();
                    System.out.println("  SSH key not found locally. Re-fetch it with:");
                    System.out.printf("  ecp get-cluster-access %s --save-key%n", name);
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Fetches a secret value from Secrets Manager using SigV4 over plain HTTPS —
     * no additional SDK dependency beyond what AwsSigV4Signer already provides.
     */
    private String fetchSecretValue(String secretArn, String resolvedRegion) throws Exception {
        String body = "{\"SecretId\":\"" + secretArn + "\"}";
        URI uri = URI.create("https://secretsmanager." + resolvedRegion + ".amazonaws.com/");

        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri);
        AwsSigV4Signer signer = AwsSigV4Signer.create(resolvedRegion);
        if (signer == null) {
            System.err.println("Error: no AWS credentials available to fetch SSH key");
            System.exit(1);
        }
        signer.sign(builder, "POST", uri, body, "secretsmanager",
            "application/x-amz-json-1.1",
            java.util.Map.of("X-Amz-Target", "secretsmanager.GetSecretValue"));
        builder.POST(HttpRequest.BodyPublishers.ofString(body));

        HttpResponse<String> resp = HttpClient.newHttpClient()
            .send(builder.build(), HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() >= 400) {
            JsonNode err = MAPPER.readTree(resp.body());
            throw new RuntimeException("Secrets Manager error: " +
                (err.has("message") ? err.get("message").asText() : resp.body()));
        }

        JsonNode node = MAPPER.readTree(resp.body());
        String secret = node.path("SecretString").asText(null);
        if (secret == null || secret.isBlank())
            throw new RuntimeException("Secret '" + secretArn + "' has no SecretString value");
        return secret;
    }

    private static String sshCommand(String publicIp, Path keyPath) {
        if (keyPath == null) return "ssh ec2-user@" + publicIp;
        return "ssh -i " + keyPath + " ec2-user@" + publicIp;
    }
}
