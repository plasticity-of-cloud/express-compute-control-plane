package ai.codriverlabs.ecp.cli.cluster;

import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import ai.codriverlabs.ecp.cli.util.KubeApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "cluster", description = "Register a cluster with EKS-DX")
public class CreateClusterCommand implements Runnable {

    @Inject EcpApiClient apiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    // package-private for testing
    KubeApiClient kubeApiClient;

    @Option(names = "--name", required = true, description = "Cluster name")
    String name;

    @Option(names = "--region", required = true, description = "AWS region")
    String region;

    @Option(names = "--kubeconfig", description = "Path to kubeconfig (default: ~/.kube/config)")
    String kubeconfig;

    @Option(names = "--issuer", description = "OIDC issuer URL (skips kubeconfig lookup when set)")
    String issuer;

    @Option(names = "--jwks-file", description = "Path to JWKS JSON file (skips kubeconfig lookup when set)")
    String jwksFile;

    @Override
    public void run() {
        try {
            String resolvedJwks;
            String resolvedIssuer;

            if (jwksFile != null && issuer != null) {
                resolvedJwks = Files.readString(Path.of(jwksFile));
                resolvedIssuer = issuer;
            } else {
                KubeApiClient kube = kubeApiClient != null ? kubeApiClient : new KubeApiClient(kubeconfig);
                resolvedJwks = kube.get("/openid/v1/jwks");
                resolvedIssuer = issuer != null ? issuer : parseIssuer(kube.get("/.well-known/openid-configuration"));
            }

            ObjectNode body = mapper.createObjectNode();
            body.put("name", name);
            body.put("issuer", resolvedIssuer);
            body.put("jwks", resolvedJwks);

            apiClient.post("/clusters", body.toString());

            System.out.printf("✓ Cluster \"%s\" registered%n", name);
            System.out.printf("  Issuer: %s%n", resolvedIssuer);
            System.out.printf("  JWKS: %d key(s)%n", countKeys(resolvedJwks));
        } catch (Exception e) {
            System.err.printf("Failed to register cluster: %s%n", e.getMessage());
            System.exit(1);
        }
    }

    String parseIssuer(String oidcConfigJson) {
        try {
            JsonNode node = mapper.readTree(oidcConfigJson);
            JsonNode issuerNode = node.get("issuer");
            if (issuerNode == null || issuerNode.asText().isBlank()) {
                throw new IllegalArgumentException("No 'issuer' field in OIDC configuration");
            }
            return issuerNode.asText();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse OIDC configuration: " + e.getMessage(), e);
        }
    }

    int countKeys(String jwksJson) {
        try {
            JsonNode node = mapper.readTree(jwksJson);
            JsonNode keys = node.get("keys");
            return keys != null && keys.isArray() ? keys.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
