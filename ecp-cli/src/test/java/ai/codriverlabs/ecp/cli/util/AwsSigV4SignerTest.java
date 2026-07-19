package ai.codriverlabs.ecp.cli.util;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.*;

class AwsSigV4SignerTest {

    private static AwsSigV4Signer testSigner(String region) {
        return new AwsSigV4Signer(
            AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"),
            Region.of(region));
    }

    @Test
    void sign_addsAuthorizationHeader() {
        URI uri = URI.create("https://api.example.com/clusters");
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri)
            .header("Content-Type", "application/json");

        testSigner("us-east-1").sign(builder, "GET", uri, null, "execute-api");

        HttpRequest request = builder.GET().build();
        assertTrue(request.headers().firstValue("Authorization").isPresent());
        assertTrue(request.headers().firstValue("Authorization").get().startsWith("AWS4-HMAC-SHA256"));
        assertTrue(request.headers().firstValue("X-Amz-Date").isPresent());
    }

    @Test
    void sign_includesRegionInCredentialScope() {
        URI uri = URI.create("https://api.example.com/clusters");
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri)
            .header("Content-Type", "application/json");

        testSigner("eu-west-1").sign(builder, "GET", uri, null, "execute-api");

        String auth = builder.GET().build().headers().firstValue("Authorization").get();
        assertTrue(auth.contains("eu-west-1/execute-api/aws4_request"));
    }

    @Test
    void sign_handlesQueryParameters() {
        URI uri = URI.create("https://api.example.com/clusters/test/workload-identities?namespace=default");
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri)
            .header("Content-Type", "application/json");

        testSigner("us-east-1").sign(builder, "GET", uri, null, "execute-api");

        assertTrue(builder.GET().build().headers().firstValue("Authorization").isPresent());
    }

    @Test
    void create_doesNotThrow() {
        assertDoesNotThrow(() -> AwsSigV4Signer.create("us-east-1"));
    }
}
