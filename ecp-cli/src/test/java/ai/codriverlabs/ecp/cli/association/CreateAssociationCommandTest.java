package ai.codriverlabs.ecp.cli.association;

import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateAssociationCommandTest {

    @Mock EcpApiClient apiClient;

    CreateAssociationCommand cmd;

    @BeforeEach
    void setUp() {
        cmd = new CreateAssociationCommand();
        cmd.apiClient = apiClient;
        cmd.clusterName = "test-cluster";
        cmd.namespace = "default";
        cmd.serviceAccount = "my-sa";
        cmd.roleArn = "arn:aws:iam::123456789012:role/test-role";
    }

    @Test
    void run_callsCorrectEndpoint() {
        when(apiClient.post(any(), any()))
            .thenReturn("{\"associationId\":\"assoc-abc\"}");

        cmd.run();

        verify(apiClient).post(eq("/clusters/test-cluster/workload-identities"), any());
    }

    @Test
    void run_sendsCorrectBody() {
        when(apiClient.post(any(), any()))
            .thenReturn("{\"associationId\":\"assoc-abc\"}");

        cmd.run();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).post(any(), bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        assertTrue(body.contains("\"namespace\":\"default\""));
        assertTrue(body.contains("\"serviceAccount\":\"my-sa\""));
        assertTrue(body.contains("\"roleArn\":\"arn:aws:iam::123456789012:role/test-role\""));
    }
}
