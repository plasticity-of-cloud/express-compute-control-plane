package cloud.plasticity.eksdx.cli.cluster;

import cloud.plasticity.eksdx.cli.util.EksDxApiClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.HttpURLConnection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test using Fabric8 KubernetesMockServer to serve
 * JWKS and OIDC discovery endpoints, verifying the full
 * CreateClusterCommand flow without a real K8s cluster.
 */
@ExtendWith(MockitoExtension.class)
@EnableKubernetesMockClient(crud = false)
class CreateClusterMockServerTest {

    static KubernetesMockServer server;
    static KubernetesClient client;

    @Mock EksDxApiClient apiClient;

    static final String JWKS_JSON = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"test-key\",\"n\":\"abc\",\"e\":\"AQAB\"}]}";
    static final String OIDC_CONFIG = "{\"issuer\":\"https://oidc.eks.us-east-1.amazonaws.com/id/ABCDEF\",\"jwks_uri\":\"https://oidc.eks.us-east-1.amazonaws.com/id/ABCDEF/keys\"}";

    @BeforeEach
    void setUp() {
        // Mock JWKS endpoint
        server.expect()
            .get().withPath("/openid/v1/jwks")
            .andReturn(HttpURLConnection.HTTP_OK, JWKS_JSON)
            .always();

        // Mock OIDC discovery endpoint
        server.expect()
            .get().withPath("/.well-known/openid-configuration")
            .andReturn(HttpURLConnection.HTTP_OK, OIDC_CONFIG)
            .always();
    }

    @Test
    void createCluster_readsJwksFromMockServer() {
        when(apiClient.post(any(), any())).thenReturn("{}");

        CreateClusterCommand cmd = new CreateClusterCommand();
        cmd.kubernetesClient = client;
        cmd.apiClient = apiClient;
        cmd.name = "test-cluster";
        cmd.region = "us-east-1";

        cmd.run();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).post(eq("/clusters"), bodyCaptor.capture());
        String body = bodyCaptor.getValue();

        // Verify JWKS was read from mock server and included in request
        assertTrue(body.contains("\"name\":\"test-cluster\""));
        assertTrue(body.contains("test-key")); // JWKS key ID
        assertTrue(body.contains("oidc.eks.us-east-1.amazonaws.com")); // issuer
    }

    @Test
    void createCluster_parsesIssuerFromOidcDiscovery() {
        when(apiClient.post(any(), any())).thenReturn("{}");

        CreateClusterCommand cmd = new CreateClusterCommand();
        cmd.kubernetesClient = client;
        cmd.apiClient = apiClient;
        cmd.name = "my-k3s";
        cmd.region = "eu-west-1";

        cmd.run();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).post(eq("/clusters"), bodyCaptor.capture());
        String body = bodyCaptor.getValue();

        assertTrue(body.contains("\"issuer\":\"https://oidc.eks.us-east-1.amazonaws.com/id/ABCDEF\""));
    }

    @Test
    void createCluster_countsKeysCorrectly() {
        when(apiClient.post(any(), any())).thenReturn("{}");

        CreateClusterCommand cmd = new CreateClusterCommand();
        cmd.kubernetesClient = client;
        cmd.apiClient = apiClient;
        cmd.name = "test";
        cmd.region = "us-east-1";

        // Capture stdout to verify key count output
        java.io.ByteArrayOutputStream capture = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.out;
        System.setOut(new java.io.PrintStream(capture));
        try {
            cmd.run();
        } finally {
            System.setOut(original);
        }

        assertTrue(capture.toString().contains("1 key(s)"));
    }
}
