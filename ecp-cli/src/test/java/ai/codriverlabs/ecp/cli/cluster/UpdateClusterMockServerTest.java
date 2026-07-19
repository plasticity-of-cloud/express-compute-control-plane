package ai.codriverlabs.ecp.cli.cluster;

import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import ai.codriverlabs.ecp.cli.util.KubeApiClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UpdateClusterMockServerTest {

    static final String JWKS = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"rotated-key\"},{\"kty\":\"EC\",\"kid\":\"new-key\"}]}";

    HttpServer server;
    KubeApiClient kubeApiClient;

    @Mock EcpApiClient apiClient;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/openid/v1/jwks", ex -> {
            byte[] body = JWKS.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();

        kubeApiClient = new KubeApiClient("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void updateCluster_refreshesJwksFromServer() {
        when(apiClient.put(any(), any())).thenReturn("{}");

        UpdateClusterCommand cmd = new UpdateClusterCommand();
        cmd.kubeApiClient = kubeApiClient;
        cmd.apiClient = apiClient;
        cmd.name = "test-cluster";
        cmd.refreshJwks = true;
        cmd.run();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(apiClient).put(eq("/clusters/test-cluster/jwks"), body.capture());
        assertTrue(body.getValue().contains("rotated-key"));
        assertTrue(body.getValue().contains("new-key"));
    }
}
