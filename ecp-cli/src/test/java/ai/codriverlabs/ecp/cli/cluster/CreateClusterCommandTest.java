package ai.codriverlabs.ecp.cli.cluster;

import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import ai.codriverlabs.ecp.cli.util.KubeApiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateClusterCommandTest {

    @Mock KubeApiClient kubeApiClient;
    @Mock EcpApiClient apiClient;

    CreateClusterCommand cmd() {
        CreateClusterCommand cmd = new CreateClusterCommand();
        cmd.kubeApiClient = kubeApiClient;
        cmd.apiClient = apiClient;
        cmd.name = "test-cluster";
        cmd.region = "us-east-1";
        return cmd;
    }

    @Test
    void run_callsApiWithCorrectBody() {
        when(kubeApiClient.get("/openid/v1/jwks")).thenReturn("{\"keys\":[{\"kty\":\"RSA\"}]}");
        when(kubeApiClient.get("/.well-known/openid-configuration")).thenReturn("{\"issuer\":\"https://oidc.example.com\"}");
        when(apiClient.post(eq("/clusters"), any())).thenReturn("{}");

        cmd().run();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(apiClient).post(eq("/clusters"), body.capture());
        assertTrue(body.getValue().contains("\"name\":\"test-cluster\""));
        assertTrue(body.getValue().contains("\"issuer\":\"https://oidc.example.com\""));
    }

    @Test
    void parseIssuer_extractsIssuer() {
        assertEquals("https://oidc.example.com",
            new CreateClusterCommand().parseIssuer("{\"issuer\":\"https://oidc.example.com\"}"));
    }

    @Test
    void parseIssuer_throws_whenNoIssuerField() {
        assertThrows(IllegalArgumentException.class,
            () -> new CreateClusterCommand().parseIssuer("{\"jwks_uri\":\"...\"}"));
    }

    @Test
    void parseIssuer_throws_whenIssuerBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> new CreateClusterCommand().parseIssuer("{\"issuer\":\"\"}"));
    }

    @Test
    void parseIssuer_throws_whenInvalidJson() {
        assertThrows(IllegalArgumentException.class,
            () -> new CreateClusterCommand().parseIssuer("not-json"));
    }

    @Test
    void countKeys_returnsCorrectCount() {
        assertEquals(2, new CreateClusterCommand().countKeys("{\"keys\":[{\"kty\":\"RSA\"},{\"kty\":\"EC\"}]}"));
    }

    @Test
    void countKeys_returnsZero_whenNoKeys() {
        assertEquals(0, new CreateClusterCommand().countKeys("{\"keys\":[]}"));
    }

    @Test
    void countKeys_returnsZero_whenInvalidJson() {
        assertEquals(0, new CreateClusterCommand().countKeys("not-json"));
    }
}
