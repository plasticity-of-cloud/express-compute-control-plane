package ai.codriverlabs.ecp.cli.association;

import ai.codriverlabs.ecp.cli.util.EcpApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ListAssociationsCommandTest {

    @Mock EcpApiClient apiClient;

    ListAssociationsCommand cmd;

    @BeforeEach
    void setUp() {
        cmd = new ListAssociationsCommand();
        cmd.apiClient = apiClient;
        cmd.clusterName = "test-cluster";
    }

    @Test
    void run_callsCorrectEndpoint_noFilters() {
        when(apiClient.get("/clusters/test-cluster/workload-identities"))
            .thenReturn("{\"associations\":[]}");

        cmd.run();

        verify(apiClient).get("/clusters/test-cluster/workload-identities");
    }

    @Test
    void run_appendsNamespaceFilter() {
        cmd.namespace = "kube-system";
        when(apiClient.get("/clusters/test-cluster/workload-identities?namespace=kube-system"))
            .thenReturn("{\"associations\":[]}");

        cmd.run();

        verify(apiClient).get("/clusters/test-cluster/workload-identities?namespace=kube-system");
    }

    @Test
    void run_appendsBothFilters() {
        cmd.namespace = "default";
        cmd.serviceAccount = "my-sa";
        when(apiClient.get("/clusters/test-cluster/workload-identities?namespace=default&serviceAccount=my-sa"))
            .thenReturn("{\"associations\":[]}");

        cmd.run();

        verify(apiClient).get("/clusters/test-cluster/workload-identities?namespace=default&serviceAccount=my-sa");
    }

    @Test
    void run_printsTable_withAssociations() {
        when(apiClient.get(anyString()))
            .thenReturn("{\"associations\":[{\"associationId\":\"assoc-1\",\"namespace\":\"default\",\"serviceAccount\":\"sa-1\",\"roleArn\":\"arn:aws:iam::123:role/r1\"}]}");

        PrintStream original = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture));
        try {
            cmd.run();
        } finally {
            System.setOut(original);
        }

        String output = capture.toString();
        assertTrue(output.contains("ASSOCIATION ID"));
        assertTrue(output.contains("assoc-1"));
        assertTrue(output.contains("default"));
        assertTrue(output.contains("sa-1"));
    }

    @Test
    void run_printsMessage_whenEmpty() {
        when(apiClient.get(anyString()))
            .thenReturn("{\"associations\":[]}");

        PrintStream original = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture));
        try {
            cmd.run();
        } finally {
            System.setOut(original);
        }

        assertTrue(capture.toString().contains("No associations found"));
    }
}
