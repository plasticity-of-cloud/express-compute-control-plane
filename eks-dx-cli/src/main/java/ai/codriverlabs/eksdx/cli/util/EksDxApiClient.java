package ai.codriverlabs.eksdx.cli.util;

import ai.codriverlabs.eksdx.cli.config.EksDxConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * JDK HttpClient wrapper for EKS-DX Lambda API calls.
 * Reads endpoint from ~/.eks-dx/config → EKS_DX_ENDPOINT env → default.
 * Signs management requests with AWS SigV4 when credentials are available.
 */
@ApplicationScoped
public class EksDxApiClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    String endpoint;
    String region;
    AwsSigV4Signer signer;

    @PostConstruct
    void init() {
        EksDxConfig config = new EksDxConfig();
        this.endpoint = config.getEndpoint();
        this.region = config.getRegion();
        this.signer = AwsSigV4Signer.create(region);
    }

    public String post(String path, String body) {
        return send("POST", path, body);
    }

    public String get(String path) {
        return send("GET", path, null);
    }

    public String put(String path, String body) {
        return send("PUT", path, body);
    }

    public String delete(String path) {
        return send("DELETE", path, null);
    }

    private String send(String method, String path, String body) {
        try {
            URI uri = URI.create(endpoint + path);
            var builder = HttpRequest.newBuilder().uri(uri);

            if (body != null) {
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            // Sign management API requests (not /assets which uses token auth)
            if (signer != null && !path.contains("/assets")) {
                signer.sign(builder, method, uri, body, "execute-api");
            } else {
                builder.header("Content-Type", "application/json");
            }

            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                System.err.printf("Error (%d): %s%n", response.statusCode(), response.body());
                System.exit(1);
            }

            return response.body();
        } catch (Exception e) {
            System.err.printf("Failed to reach EKS-DX service at %s: %s%n", endpoint, e.getMessage());
            System.exit(1);
            return null;
        }
    }
}
