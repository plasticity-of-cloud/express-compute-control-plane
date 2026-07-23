package ai.codriverlabs.ecp.cli.util;

import ai.codriverlabs.ecp.cli.config.EcpConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * JDK HttpClient wrapper for Express Compute Lambda API calls.
 * Reads endpoint from ~/.ecp/config → ECP_ENDPOINT env → default.
 * Signs management requests with AWS SigV4 when credentials are available.
 */
@ApplicationScoped
public class EcpApiClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    String endpoint;
    String region;
    AwsSigV4Signer signer;

    @PostConstruct
    void init() {
        EcpConfig config = new EcpConfig();
        this.endpoint = config.getEndpoint();
        this.region = config.getRegion();
        this.signer = AwsSigV4Signer.create(region);
    }

    public String post(String path, String body) {
        return send("POST", path, body);
    }

    /**
     * POST to a Lambda Function URL (signs with service=lambda, no 29s API Gateway limit).
     * Returns the response body.
     */
    public String postFunctionUrl(String url, String body, String region) {
        try {
            URI uri = URI.create(url);
            var builder = HttpRequest.newBuilder().uri(uri)
                .method("POST", HttpRequest.BodyPublishers.ofString(body));
            AwsSigV4Signer fnSigner = AwsSigV4Signer.create(region);
            if (fnSigner != null) fnSigner.sign(builder, "POST", uri, body, "lambda");
            else builder.header("Content-Type", "application/json");
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                String b = response.body();
                try { var n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(b);
                    String msg = n.has("message") ? n.get("message").asText() : b;
                    System.err.println("Error: " + msg);
                } catch (Exception ignored) { System.err.println("Error: " + b); }
                System.exit(1);
            }
            return response.body();
        } catch (Exception e) {
            System.err.printf("Error: failed to reach provisioning URL: %s%n", e.getMessage());
            System.exit(1);
            return null;
        }
    }

    public void deleteFunctionUrl(String url, String region) {
        try {
            URI uri = URI.create(url);
            var builder = HttpRequest.newBuilder().uri(uri).DELETE();
            AwsSigV4Signer fnSigner = AwsSigV4Signer.create(region);
            if (fnSigner != null) fnSigner.sign(builder, "DELETE", uri, null, "lambda");
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                String b = response.body();
                try { var n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(b);
                    String msg = n.has("message") ? n.get("message").asText() : b;
                    System.err.println("Error: " + msg);
                } catch (Exception ignored) { System.err.println("Error: " + b); }
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.printf("Error: failed to reach Function URL: %s%n", e.getMessage());
            System.exit(1);
        }
    }

    public String get(String path) {
        return send("GET", path, null);
    }

    /** Returns the HTTP status code without throwing on 4xx/5xx. */
    public int getStatus(String path) {
        return getStatusOnUrl(endpoint, path);
    }

    /** Returns the HTTP status code for a given base URL without throwing on 4xx/5xx. */
    public int getStatusOnUrl(String baseUrl, String path) {
        return getStatusOnUrl(baseUrl, path, "execute-api");
    }

    /** Returns the HTTP status code, signing with the specified service name. */
    public int getStatusOnUrl(String baseUrl, String path, String service) {
        try {
            URI uri = URI.create(baseUrl.replaceAll("/$", "") + path);
            var builder = HttpRequest.newBuilder().uri(uri).GET()
                .timeout(java.time.Duration.ofSeconds(10));
            if (signer != null) signer.sign(builder, "GET", uri, null, service);
            else builder.header("Content-Type", "application/json");
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    public String put(String path, String body) {
        return send("PUT", path, body);
    }

    public String delete(String path) {
        return send("DELETE", path, null);
    }

    public String deleteOnUrl(String baseUrl, String path) {
        return sendOnUrl("DELETE", baseUrl, path, null);
    }

    /** POST to a given base URL, returns status code without throwing on 4xx/5xx. */
    public int postStatusOnUrl(String baseUrl, String path, String body) {
        return postStatusOnUrl(baseUrl, path, body, "execute-api");
    }

    /** POST to a given base URL, returns status code without throwing on 4xx/5xx. */
    public int postStatusOnUrl(String baseUrl, String path, String body, String service) {
        try {
            URI uri = URI.create(baseUrl.replaceAll("/$", "") + path);
            var builder = HttpRequest.newBuilder().uri(uri)
                .method("POST", HttpRequest.BodyPublishers.ofString(body))
                .timeout(java.time.Duration.ofSeconds(35));
            if (signer != null) signer.sign(builder, "POST", uri, body, service);
            else builder.header("Content-Type", "application/json");
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    /** GET body as string from a given base URL, returns null on error. */
    public String getBodyOnUrl(String baseUrl, String path) {
        return getBodyOnUrl(baseUrl, path, "execute-api");
    }

    /** GET body as string from a given base URL with explicit service signing, returns null on error. */
    public String getBodyOnUrl(String baseUrl, String path, String service) {
        try {
            URI uri = URI.create(baseUrl.replaceAll("/$", "") + path);
            var builder = HttpRequest.newBuilder().uri(uri).GET()
                .timeout(java.time.Duration.ofSeconds(35));
            if (signer != null) signer.sign(builder, "GET", uri, null, service);
            else builder.header("Content-Type", "application/json");
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 400 ? response.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** DELETE that returns the status code without throwing on 4xx/5xx. */
    public int deleteStatusOnUrl(String baseUrl, String path) {
        return deleteStatusOnUrl(baseUrl, path, "execute-api");
    }

    /** DELETE that returns the status code, signing with the specified service name. */
    public int deleteStatusOnUrl(String baseUrl, String path, String service) {
        try {
            URI uri = URI.create(baseUrl.replaceAll("/$", "") + path);
            var builder = HttpRequest.newBuilder().uri(uri)
                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                .timeout(java.time.Duration.ofSeconds(35));
            if (signer != null) signer.sign(builder, "DELETE", uri, null, service);
            else builder.header("Content-Type", "application/json");
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (java.net.http.HttpTimeoutException e) {
            // Lambda Function URL / API Gateway may time out; Lambda keeps running.
            return 503;
        } catch (Exception e) {
            return -1;
        }
    }

    private String send(String method, String path, String body) {
        return sendOnUrl(method, endpoint, path, body);
    }

    private String sendOnUrl(String method, String baseUrl, String path, String body) {
        try {
            URI uri = URI.create(baseUrl.replaceAll("/$", "") + path);
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
                String body2 = response.body();
                try {
                    var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body2);
                    String msg = node.has("message") ? node.get("message").asText() : body2;
                    System.err.println("Error: " + msg);
                } catch (Exception ignored) {
                    System.err.println("Error: " + body2);
                }
                System.exit(1);
            }

            return response.body();
        } catch (Exception e) {
            System.err.printf("Failed to reach Express Compute service at %s: %s%n", baseUrl, e.getMessage());
            System.exit(1);
            return null;
        }
    }

}
