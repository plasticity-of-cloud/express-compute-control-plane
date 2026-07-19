package ai.codriverlabs.ecp.cli.association;

import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeleteAssociationCommandTest {

    @Mock EcpApiClient apiClient;

    DeleteAssociationCommand cmd;

    @BeforeEach
    void setUp() {
        cmd = new DeleteAssociationCommand();
        cmd.apiClient = apiClient;
        cmd.clusterName = "test-cluster";
        cmd.associationId = "assoc-abc";
    }

    @Test
    void run_callsCorrectEndpoint() {
        when(apiClient.delete("/clusters/test-cluster/workload-identities/assoc-abc"))
            .thenReturn("");

        cmd.run();

        verify(apiClient).delete("/clusters/test-cluster/workload-identities/assoc-abc");
    }
}
