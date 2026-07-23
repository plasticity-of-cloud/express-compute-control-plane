package ai.codriverlabs.ecp.cli.tenant;

import ai.codriverlabs.ecp.cli.config.EcpConfig;
import ai.codriverlabs.ecp.cli.util.AwsSigV4Signer;
import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

@Command(name = "tenant", description = "Provision a new Express Compute cluster")
public class CreateTenantCommand implements Runnable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = "--cluster-name", required = true, description = "User-visible cluster name (e.g. my-dev-cluster)")
    String clusterName;

    @Option(names = "--unmanaged", description = "Create a record-only tenant for a standalone cluster (k3s, microk8s, etc.)")
    boolean unmanaged;

    @Option(names = "--arch", defaultValue = "arm64", description = "CPU architecture: arm64 or x86_64")
    String arch;

    @Option(names = "--pricing", defaultValue = "spot", description = "EC2 pricing model: spot or ondemand")
    String ec2PricingModel;

    @Option(names = "--k8s-version", defaultValue = "1.35", description = "Kubernetes version")
    String k8sVersion;

    @Option(names = "--disk-size", defaultValue = "20", description = "Root disk size in GB")
    int diskSizeGb;

    @Option(names = "--eip", description = "Assign Elastic IP")
    boolean assignElasticIp;

    @Option(names = "--ssh-cidr", description = "CIDR allowed for SSH (default: auto-detect caller IP)")
    String sshCidr;

    @Option(names = "--wait", description = "Stream progress and wait for completion")
    boolean wait;

    @Option(names = "--output", defaultValue = "text", description = "Output format: text or json")
    String output;

    @Option(names = "--stream-url", description = "Lambda Function URL for SSE stream (overrides config)")
    String streamUrl;

    @Inject EcpApiClient apiClient;

    @Override
    public void run() {
        try {
            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("clusterName", clusterName);
            body.put("managed", !unmanaged);
            if (!unmanaged) {
                body.put("arch", arch);
                body.put("ec2PricingModel", ec2PricingModel);
                body.put("k8sVersion", k8sVersion);
                body.put("diskSizeGb", diskSizeGb);
                body.put("assignElasticIp", assignElasticIp);
                if (sshCidr != null) body.put("sshCidr", sshCidr);
            }

            EcpConfig config = new EcpConfig();
            String region = config.getRegion();

            String provisioningUrl = config.getProvisioningUrl();
            if (provisioningUrl == null) {
                System.err.println("Error: provisioning URL not configured. Set ECP_PROVISIONING_URL or run 'ecp configure'.");
                System.exit(1);
            }
            String provisionUrl = provisioningUrl.replaceAll("/$", "") + "/tenants";
            String responseBody = apiClient.postFunctionUrl(provisionUrl, MAPPER.writeValueAsString(body), region);

            JsonNode resp = MAPPER.readTree(responseBody);
            String tenantId = resp.path("tenantId").asText();

            if ("text".equals(output)) {
                System.out.printf("Created tenant %s (cluster: %s, %s)%n",
                    tenantId, clusterName, unmanaged ? "unmanaged" : "managed");
            } else {
                System.out.println(responseBody);
            }

            if (unmanaged || !wait) return;

            String resolvedStreamUrl = streamUrl != null ? streamUrl : config.getStreamUrl();
            if (resolvedStreamUrl == null) {
                System.err.println("Error: stream URL not configured. Set ECP_STREAM_URL or run 'ecp configure'.");
                System.exit(1);
            }
            String url = resolvedStreamUrl.stripTrailing().replaceAll("/$", "")
                + "/tenants/" + tenantId + "/stream";

            streamProgress(url, region, tenantId, System.currentTimeMillis(), config);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private void streamProgress(String url, String region, String tenantId, long startTime, EcpConfig config) {
        try {
            URI uri = URI.create(url);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .header("Accept", "text/event-stream")
                .GET();

            AwsSigV4Signer signer = AwsSigV4Signer.create(region);
            if (signer != null) signer.sign(builder, "GET", uri, null, "lambda");

            HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() >= 400) {
                System.err.println("Stream error: HTTP " + response.statusCode());
                System.exit(1);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String json = line.substring(5).trim();
                    JsonNode event = MAPPER.readTree(json);

                    String state = event.path("state").asText();
                    String phase = event.path("phase").asText();
                    int progress = event.path("progress").asInt();

                    if ("text".equals(output)) {
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        System.out.printf("  [%3d%%] %s  (+%ds)%n", progress, phase, elapsed);
                    }

                    if ("ready".equals(state)) {
                        String publicIp = event.path("publicIp").asText(null);
                        String sshKey = event.path("sshPrivateKey").asText(null);

                        if ("json".equals(output)) {
                            System.out.println(json);
                        } else {
                            System.out.println("\nTenant ready.");
                            if (publicIp != null) System.out.println("Public IP: " + publicIp);
                        }

                        if (sshKey != null) {
                            Path keyPath = config.tenantSshKeyPath(region, tenantId);
                            Files.createDirectories(keyPath.getParent());
                            Files.writeString(keyPath, sshKey);
                            try {
                                Files.setPosixFilePermissions(keyPath, PosixFilePermissions.fromString("rw-------"));
                            } catch (UnsupportedOperationException ignored) {}
                            if ("text".equals(output))
                                System.out.println("SSH key saved: " + keyPath);
                        }
                        return;
                    }

                    if ("failed".equals(state)) {
                        System.err.println("Provisioning failed: " + event.path("error").asText());
                        System.exit(1);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Stream error: " + e.getMessage());
            System.exit(1);
        }
    }
}
