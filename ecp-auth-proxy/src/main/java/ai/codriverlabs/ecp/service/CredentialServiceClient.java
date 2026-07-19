package ai.codriverlabs.ecp.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Forwards credential exchange requests to the EKS-DX Lambda service.
 * Attaches the proxy's own SA token (audience: ecp.codriverlabs.ai) as
 * Authorization header so the Lambda can verify the request originated from
 * a legitimate proxy inside the registered cluster.
 */
@ApplicationScoped
public class CredentialServiceClient {

    private static final Logger LOG = Logger.getLogger(CredentialServiceClient.class);
    private static final String PROXY_TOKEN_PATH = "/var/run/secrets/express-compute/token";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @ConfigProperty(name = "express-compute.endpoint")
    String endpoint;

    public ForwardResult forward(String clusterName, String requestBody) {
        String url = endpoint + "/clusters/" + clusterName + "/assets";
        LOG.debugf("Forwarding to %s", url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + readProxyToken())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            LOG.infof("Lambda response: %d for cluster %s", response.statusCode(), clusterName);
            return new ForwardResult(response.statusCode(), response.body());

        } catch (Exception e) {
            LOG.errorf("Failed to forward to Lambda: %s", e.getMessage());
            throw new RuntimeException("Failed to reach EKS-DX service: " + e.getMessage(), e);
        }
    }

    private String readProxyToken() throws Exception {
        return Files.readString(Path.of(PROXY_TOKEN_PATH)).strip();
    }

    public record ForwardResult(int statusCode, String body) {}
}
