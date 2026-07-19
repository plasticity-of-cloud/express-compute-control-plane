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

/**
 * Integration test: real JDK HttpServer serves JWKS + OIDC endpoints;
 * KubeApiClient connects to it directly (no kubeconfig needed).
 */
@ExtendWith(MockitoExtension.class)
class CreateClusterMockServerTest {

    static final String JWKS = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"test-key\"}]}";
    static final String OIDC = "{\"issuer\":\"https://oidc.eks.us-east-1.amazonaws.com/id/ABCDEF\"}";

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
        server.createContext("/.well-known/openid-configuration", ex -> {
            byte[] body = OIDC.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();

        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        kubeApiClient = new KubeApiClient(baseUrl);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void createCluster_readsJwksFromServer() {
        when(apiClient.post(any(), any())).thenReturn("{}");

        CreateClusterCommand cmd = new CreateClusterCommand();
        cmd.kubeApiClient = kubeApiClient;
        cmd.apiClient = apiClient;
        cmd.name = "test-cluster";
        cmd.region = "us-east-1";
        cmd.run();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(apiClient).post(eq("/clusters"), body.capture());
        assertTrue(body.getValue().contains("test-key"));
        assertTrue(body.getValue().contains("oidc.eks.us-east-1.amazonaws.com"));
    }

    @Test
    void createCluster_countsKeysCorrectly() {
        when(apiClient.post(any(), any())).thenReturn("{}");

        CreateClusterCommand cmd = new CreateClusterCommand();
        cmd.kubeApiClient = kubeApiClient;
        cmd.apiClient = apiClient;
        cmd.name = "test";
        cmd.region = "us-east-1";

        var capture = new java.io.ByteArrayOutputStream();
        var original = System.out;
        System.setOut(new java.io.PrintStream(capture));
        try { cmd.run(); } finally { System.setOut(original); }

        assertTrue(capture.toString().contains("1 key(s)"));
    }
}
