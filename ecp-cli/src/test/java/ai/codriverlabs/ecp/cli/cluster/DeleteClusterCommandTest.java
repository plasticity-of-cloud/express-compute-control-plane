package ai.codriverlabs.ecp.cli.cluster;

import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeleteClusterCommandTest {

    @Mock EcpApiClient apiClient;

    DeleteClusterCommand cmd;

    @BeforeEach
    void setUp() {
        cmd = new DeleteClusterCommand();
        cmd.apiClient = apiClient;
        cmd.name = "test-cluster";
    }

    @Test
    void run_callsCorrectEndpoint() {
        when(apiClient.delete("/clusters/test-cluster")).thenReturn("");

        cmd.run();

        verify(apiClient).delete("/clusters/test-cluster");
    }
}
