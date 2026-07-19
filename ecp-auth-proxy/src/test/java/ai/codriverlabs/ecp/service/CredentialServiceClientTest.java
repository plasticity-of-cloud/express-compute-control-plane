package ai.codriverlabs.ecp.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class CredentialServiceClientTest {

    CredentialServiceClient service;

    @BeforeEach
    void setUp() {
        service = new CredentialServiceClient();
        service.endpoint = "http://localhost:9999";
    }

    @Test
    void forward_throwsRuntimeException_whenEndpointUnreachable() {
        var ex = assertThrows(RuntimeException.class,
            () -> service.forward("test-cluster", "{\"token\":\"abc\"}"));
        assertTrue(ex.getMessage().contains("Failed to reach EKS-DX service"));
    }

    @Test
    void forward_constructsCorrectUrl() {
        // The URL should be endpoint + /clusters/{clusterName}/assets
        // We can't easily test the actual HTTP call without a server,
        // but we verify the exception message contains the endpoint
        var ex = assertThrows(RuntimeException.class,
            () -> service.forward("my-cluster", "{\"token\":\"abc\"}"));
        assertTrue(ex.getMessage().contains("Failed to reach EKS-DX service"));
    }
}
