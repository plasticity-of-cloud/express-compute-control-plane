package cloud.plasticity.eksauth.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Forwards credential exchange requests to the EKS-DX Lambda service.
 * The proxy validates the token locally via TokenReview (fast-fail),
 * then delegates the full flow (JWKS validation, association lookup,
 * STS AssumeRole) to the Lambda.
 */
@ApplicationScoped
public class LambdaForwardingService {

    private static final Logger LOG = Logger.getLogger(LambdaForwardingService.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @ConfigProperty(name = "eks-dx.endpoint")
    String endpoint;

    /**
     * Forward the credential exchange request to the Lambda service.
     *
     * @param clusterName the cluster name from the path
     * @param requestBody the raw JSON body (contains the token)
     * @return the Lambda response body (credentials JSON)
     */
    public ForwardResult forward(String clusterName, String requestBody) {
        String url = endpoint + "/clusters/" + clusterName + "/assets";
        LOG.debugf("Forwarding to %s", url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
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

    public record ForwardResult(int statusCode, String body) {}
}
