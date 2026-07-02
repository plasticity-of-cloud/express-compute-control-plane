package ai.codriverlabs.eksdx.cli.util;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AWS SigV4 signer backed by the AWS SDK DefaultCredentialsProvider.
 * Handles env vars, ~/.aws/credentials, EC2 instance profile (IMDS), ECS, SSO, etc.
 *
 * Credentials are resolved with a 10s deadline to prevent indefinite hangs when
 * the credential chain includes a slow/unavailable source (IMDS, STS, SSO).
 * Resolved credentials are cached and reused across requests.
 */
public class AwsSigV4Signer {

    private static final int CREDENTIAL_TIMEOUT_SECONDS = 10;

    private final AwsCredentialsProvider credentialsProvider;
    private final Region region;
    private final Aws4Signer signer = Aws4Signer.create();

    /** Cached credentials — refreshed lazily when null or expired. */
    private final AtomicReference<AwsCredentials> cachedCredentials = new AtomicReference<>();

    AwsSigV4Signer(AwsCredentialsProvider credentialsProvider, Region region,
                   AwsCredentials initialCredentials) {
        this.credentialsProvider = credentialsProvider;
        this.region = region;
        this.cachedCredentials.set(initialCredentials);
    }

    public static AwsSigV4Signer create(String region) {
        try {
            var provider = DefaultCredentialsProvider.create();
            // Resolve once at startup with a hard deadline — fails fast if unavailable
            AwsCredentials creds = resolveWithTimeout(provider, CREDENTIAL_TIMEOUT_SECONDS);
            if (creds == null) return null;
            return new AwsSigV4Signer(provider, Region.of(region), creds);
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
                .awsCredentials(credentials())
                .signingRegion(region)
                .signingName(service)
                .build());

        signed.headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("Host")) {
                values.forEach(value -> builder.setHeader(name, value));
            }
        });
    }

    /**
     * Returns cached credentials. Resolved once at create() time; no per-call refresh.
     * CLI sessions are short-lived so credential expiry mid-session is not a concern.
     */
    private AwsCredentials credentials() {
        return cachedCredentials.get();
    }

    private static AwsCredentials resolveWithTimeout(AwsCredentialsProvider provider, int timeoutSeconds) {
        try {
            return CompletableFuture
                .supplyAsync(provider::resolveCredentials)
                .get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }
}
