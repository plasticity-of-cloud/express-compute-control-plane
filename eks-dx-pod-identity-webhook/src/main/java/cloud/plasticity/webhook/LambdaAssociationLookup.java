package cloud.plasticity.webhook;

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
 * Looks up Pod Identity Associations via the EKS-DX Lambda API.
 * Authenticates using a projected SA token with audience eks-dx.plasticity.cloud.
 */
@ApplicationScoped
public class LambdaAssociationLookup {

    private static final Logger LOG = Logger.getLogger(LambdaAssociationLookup.class);
    private static final String TOKEN_PATH = "/var/run/secrets/eks-dx/token";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @ConfigProperty(name = "eks-dx.endpoint")
    String endpoint;

    @ConfigProperty(name = "eks.cluster-name")
    String clusterName;

    public boolean hasAssociation(String namespace, String serviceAccount) {
        try {
            String url = endpoint + "/clusters/" + clusterName +
                "/pod-identity-associations?namespace=" + namespace +
                "&serviceAccount=" + serviceAccount;

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .GET();

            // Attach SA token for authentication if available
            String token = readToken();
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.debugf("Association lookup returned %d for %s/%s",
                    response.statusCode(), namespace, serviceAccount);
                return false;
            }

            // Check if the response contains any associations
            String body = response.body();
            return body.contains("\"associationId\"");

        } catch (Exception e) {
            LOG.errorf("Failed to look up association for %s/%s: %s",
                namespace, serviceAccount, e.getMessage());
            return false;
        }
    }

    String readToken() {
        try {
            Path tokenPath = Path.of(TOKEN_PATH);
            if (Files.exists(tokenPath)) {
                return Files.readString(tokenPath).trim();
            }
        } catch (Exception e) {
            LOG.debugf("Could not read SA token: %s", e.getMessage());
        }
        return null;
    }
}
