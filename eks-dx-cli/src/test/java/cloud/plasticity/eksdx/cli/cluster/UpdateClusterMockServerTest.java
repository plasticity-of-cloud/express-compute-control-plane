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
 * Integration test for JWKS refresh using Fabric8 mock K8s server.
 */
@ExtendWith(MockitoExtension.class)
@EnableKubernetesMockClient(crud = false)
class UpdateClusterMockServerTest {

    static KubernetesMockServer server;
    static KubernetesClient client;

    @Mock EksDxApiClient apiClient;

    @BeforeEach
    void setUp() {
        server.expect()
            .get().withPath("/openid/v1/jwks")
            .andReturn(HttpURLConnection.HTTP_OK,
                "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"rotated-key\"},{\"kty\":\"EC\",\"kid\":\"new-key\"}]}")
            .always();
    }

    @Test
    void updateCluster_refreshesJwksFromMockServer() {
        when(apiClient.put(any(), any())).thenReturn("{}");

        UpdateClusterCommand cmd = new UpdateClusterCommand();
        cmd.kubernetesClient = client;
        cmd.apiClient = apiClient;
        cmd.name = "test-cluster";
        cmd.refreshJwks = true;

        cmd.run();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).put(eq("/clusters/test-cluster/jwks"), bodyCaptor.capture());
        String body = bodyCaptor.getValue();

        assertTrue(body.contains("rotated-key"));
        assertTrue(body.contains("new-key"));
    }
}
