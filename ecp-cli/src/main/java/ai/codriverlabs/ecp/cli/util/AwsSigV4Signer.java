package ai.codriverlabs.ecp.cli.util;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

/**
 * AWS SigV4 signer backed by the AWS SDK DefaultCredentialsProvider.
 * Handles env vars, ~/.aws/credentials, EC2 instance profile (IMDS), ECS, SSO, etc.
 *
 * Credentials are resolved once at create() time. Per-call refresh is not needed
 * for short-lived CLI sessions.
 */
public class AwsSigV4Signer {

    private final AwsCredentials credentials;
    private final Region region;
    private final Aws4Signer signer = Aws4Signer.create();

    AwsSigV4Signer(AwsCredentials credentials, Region region) {
        this.credentials = credentials;
        this.region = region;
    }

    /**
     * Creates a signer by resolving credentials synchronously.
     * Returns null if no credentials are available.
     */
    public static AwsSigV4Signer create(String region) {
        try {
            AwsCredentialsProvider provider = DefaultCredentialsProvider.builder()
                    .reuseLastProviderEnabled(true)
                    .build();
            AwsCredentials creds = provider.resolveCredentials();
            return new AwsSigV4Signer(creds, Region.of(region));
        } catch (Exception e) {
            return null;
        }
    }

    public void sign(HttpRequest.Builder builder, String method, URI uri,
                     String body, String service) {
        sign(builder, method, uri, body, service, "application/json", java.util.Map.of());
    }

    public void sign(HttpRequest.Builder builder, String method, URI uri,
                     String body, String service, String contentType) {
        sign(builder, method, uri, body, service, contentType, java.util.Map.of());
    }

    public void sign(HttpRequest.Builder builder, String method, URI uri,
                     String body, String service, String contentType,
                     java.util.Map<String, String> extraHeaders) {
        byte[] payload = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];

        var sdkRequestBuilder = SdkHttpFullRequest.builder()
            .method(SdkHttpMethod.fromValue(method))
            .uri(uri)
            .putHeader("Content-Type", contentType);
        extraHeaders.forEach(sdkRequestBuilder::putHeader);
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                sdkRequestBuilder.putRawQueryParameter(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        if (payload.length > 0) {
            sdkRequestBuilder.contentStreamProvider(() -> new ByteArrayInputStream(payload));
        }

        var signed = signer.sign(sdkRequestBuilder.build(),
            Aws4SignerParams.builder()
                .awsCredentials(credentials)
                .signingRegion(region)
                .signingName(service)
                .build());

        signed.headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("Host")) {
                values.forEach(value -> builder.setHeader(name, value));
            }
        });
    }
}
