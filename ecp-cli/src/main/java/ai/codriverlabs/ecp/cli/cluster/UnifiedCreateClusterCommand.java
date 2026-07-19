package ai.codriverlabs.ecp.cli.cluster;

import ai.codriverlabs.ecp.cli.config.EcpConfig;
import ai.codriverlabs.ecp.cli.util.AwsSigV4Signer;
import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import ai.codriverlabs.ecp.cli.util.KubeApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;

/**
 * Unified create-cluster command. Server infers mode from request:
 *
 * managed (default): Full tenant provisioning — generates PKI, launches EC2, pre-registers JWKS.
 * self-managed: Registers an externally-managed cluster with user-provided JWKS/issuer.
 */
@Command(name = "create-cluster", description = "Create a managed cluster or register a self-managed one")
public class UnifiedCreateClusterCommand implements Runnable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", description = "Cluster name")
    String name;

    // --- Managed mode options ---
    @Option(names = "--arch", defaultValue = "arm64", description = "CPU architecture: arm64 or x86_64")
    String arch;

    @Option(names = "--pricing", defaultValue = "spot", description = "EC2 pricing: spot or ondemand")
    String ec2PricingModel;

    @Option(names = "--k8s-version", defaultValue = "1.35", description = "Kubernetes version")
    String k8sVersion;

    @Option(names = "--disk-size", defaultValue = "20", description = "Root disk size in GB")
    int diskSizeGb;

    @Option(names = "--eip", description = "Assign Elastic IP")
    boolean assignElasticIp;

    @Option(names = "--ssh-cidr", description = "CIDR for SSH access")
    String sshCidr;

    @Option(names = "--wait", description = "Stream progress and wait for completion")
    boolean wait;

    // --- Self-managed mode options (presence triggers self-managed mode) ---
    @Option(names = "--jwks-uri", description = "JWKS endpoint URL (triggers self-managed mode)")
    String jwksUri;

    @Option(names = "--jwks-file", description = "Path to JWKS JSON file (triggers self-managed mode)")
    String jwksFile;

    @Option(names = "--issuer", description = "SA token issuer URL (required with --jwks-uri/--jwks-file)")
    String issuer;

    @Option(names = "--kubeconfig", description = "Path to kubeconfig for JWKS/issuer discovery")
    String kubeconfig;

    // --- Common ---
    @Option(names = "--region", description = "AWS region")
    String region;

    @Option(names = "--output", defaultValue = "text", description = "Output format: text or json")
    String output;

    @Inject EcpApiClient apiClient;

    @Override
    public void run() {
        boolean selfManaged = (jwksUri != null || jwksFile != null || issuer != null);
        if (selfManaged) {
            runSelfManaged();
        } else {
            runManaged();
        }
    }

    private void validateManagedMode() {
        // no-op — mode is inferred, not explicit
    }

    private void runManaged() {
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("clusterName", name);
            body.put("arch", arch);
            body.put("ec2PricingModel", ec2PricingModel);
            body.put("k8sVersion", k8sVersion);
            body.put("diskSizeGb", diskSizeGb);
            body.put("assignElasticIp", assignElasticIp);
            if (sshCidr != null) body.put("sshCidr", sshCidr);

            EcpConfig config = new EcpConfig();
            String resolvedRegion = region != null ? region : config.getRegion();

            String provisioningUrl = config.getProvisioningUrl();
            if (provisioningUrl == null) {
                System.err.println("Error: provisioning URL not configured. Set ECP_PROVISIONING_URL or run 'ecp configure'.");
                System.exit(1);
            }
            String url = provisioningUrl.replaceAll("/$", "") + "/clusters";
            String responseBody = apiClient.postFunctionUrl(url, MAPPER.writeValueAsString(body), resolvedRegion);

            JsonNode resp = MAPPER.readTree(responseBody);
            String tenantId = resp.path("tenantId").asText();

            if ("text".equals(output)) {
                System.out.printf("Created cluster \"%s\" (tenant: %s, managed)%n", name, tenantId);
            } else {
                System.out.println(responseBody);
            }

            if (!wait) return;

            String streamUrl = config.getStreamUrl();
            if (streamUrl == null) {
                System.err.println("Error: stream URL not configured.");
                System.exit(1);
            }
            String streamEndpoint = streamUrl.stripTrailing().replaceAll("/$", "")
                + "/tenants/" + tenantId + "/stream";
            streamProgress(streamEndpoint, resolvedRegion, tenantId, config);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private void runSelfManaged() {
        try {
            String resolvedJwks;
            String resolvedIssuer;

            if ((jwksFile != null || jwksUri != null) && issuer != null) {
                resolvedJwks = jwksFile != null
                    ? Files.readString(Path.of(jwksFile))
                    : fetchUrl(jwksUri);
                resolvedIssuer = issuer;
            } else if (jwksFile == null && jwksUri == null && issuer == null) {
                // Try kubeconfig discovery
                KubeApiClient kube = new KubeApiClient(kubeconfig);
                resolvedJwks = kube.get("/openid/v1/jwks");
                resolvedIssuer = parseIssuer(kube.get("/.well-known/openid-configuration"));
            } else {
                System.err.println("Error: Self-managed mode requires the following parameters:");
                System.err.println("         --jwks-uri <url>  or  --jwks-file <path>    (cluster's JWKS)");
                System.err.println("         --issuer <url>                               (SA token issuer URL)");
                System.err.println("       Or: provide neither and connect via --kubeconfig for auto-discovery.");
                System.exit(1);
                return;
            }

            EcpConfig config = new EcpConfig();
            String resolvedRegion = region != null ? region : config.getRegion();

            String provisioningUrl = config.getProvisioningUrl();
            if (provisioningUrl == null) {
                System.err.println("Error: provisioning URL not configured. Set ECP_PROVISIONING_URL or run 'ecp configure'.");
                System.exit(1);
            }

            var body = new LinkedHashMap<String, Object>();
            body.put("clusterName", name);
            body.put("jwks", resolvedJwks);
            body.put("issuer", resolvedIssuer);

            String url = provisioningUrl.replaceAll("/$", "") + "/clusters";
            String responseBody = apiClient.postFunctionUrl(url, MAPPER.writeValueAsString(body), resolvedRegion);

            if ("text".equals(output)) {
                System.out.printf("✓ Cluster \"%s\" registered (self-managed)%n", name);
                System.out.printf("  Issuer: %s%n", resolvedIssuer);
            } else {
                System.out.println(responseBody);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private void streamProgress(String url, String resolvedRegion, String tenantId, EcpConfig config) {
        try {
            URI uri = URI.create(url);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri).header("Accept", "text/event-stream").GET();

            AwsSigV4Signer signer = AwsSigV4Signer.create(resolvedRegion);
            if (signer != null) signer.sign(builder, "GET", uri, null, "lambda");

            HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() >= 400) {
                System.err.println("Stream error: HTTP " + response.statusCode());
                System.exit(1);
            }

            long startTime = System.currentTimeMillis();
            String lastPhase = "";
            int lastProgress = -1;
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
                        // Deduplicate consecutive identical events (gap-fill repeats)
                        if (phase.equals(lastPhase) && progress == lastProgress) continue;
                        lastPhase = phase;
                        lastProgress = progress;
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        System.out.printf("  [%3d%%] %s  (+%ds)%n", progress, phase, elapsed);
                    }

                    if ("ready".equals(state)) {
                        String publicIp = event.path("publicIp").asText(null);
                        String sshKey = event.path("sshPrivateKey").asText(null);
                        if ("text".equals(output)) {
                            System.out.println("\n✓ Cluster ready.");
                            if (publicIp != null) System.out.println("  Public IP: " + publicIp);
                        } else {
                            System.out.println(json);
                        }
                        if (sshKey != null) {
                            Path keyPath = config.tenantSshKeyPath(resolvedRegion, tenantId);
                            Files.createDirectories(keyPath.getParent());
                            Files.writeString(keyPath, sshKey);
                            try { Files.setPosixFilePermissions(keyPath, PosixFilePermissions.fromString("rw-------")); }
                            catch (UnsupportedOperationException ignored) {}
                            if ("text".equals(output)) System.out.println("  SSH key: " + keyPath);
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

    private String parseIssuer(String oidcConfigJson) {
        try {
            JsonNode node = MAPPER.readTree(oidcConfigJson);
            return node.get("issuer").asText();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse OIDC issuer: " + e.getMessage());
        }
    }

    private String fetchUrl(String url) {
        try {
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode());
            return resp.body();
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch " + url + ": " + e.getMessage());
        }
    }
}
