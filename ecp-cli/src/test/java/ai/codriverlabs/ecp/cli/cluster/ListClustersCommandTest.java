package ai.codriverlabs.ecp.cli.cluster;

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
class ListClustersCommandTest {

    @Mock EcpApiClient apiClient;

    ListClustersCommand cmd;

    @BeforeEach
    void setUp() {
        cmd = new ListClustersCommand();
        cmd.apiClient = apiClient;
    }

    @Test
    void run_callsCorrectEndpoint() {
        when(apiClient.get("/clusters"))
            .thenReturn("{\"clusters\":[]}");

        cmd.run();

        verify(apiClient).get("/clusters");
    }

    @Test
    void run_printsTableWithClusters() {
        when(apiClient.get("/clusters"))
            .thenReturn("{\"clusters\":[{\"clusterName\":\"cluster-a\",\"issuer\":\"https://a.example.com\",\"createdAt\":\"2025-01-01\"},{\"clusterName\":\"cluster-b\",\"issuer\":\"https://b.example.com\",\"createdAt\":\"2025-01-02\"}]}");

        PrintStream original = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture));
        try {
            cmd.run();
        } finally {
            System.setOut(original);
        }

        String output = capture.toString();
        assertTrue(output.contains("NAME"));
        assertTrue(output.contains("ISSUER"));
        assertTrue(output.contains("cluster-a"));
        assertTrue(output.contains("cluster-b"));
    }

    @Test
    void run_printsMessage_whenEmpty() {
        when(apiClient.get("/clusters"))
            .thenReturn("{\"clusters\":[]}");

        PrintStream original = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture));
        try {
            cmd.run();
        } finally {
            System.setOut(original);
        }

        assertTrue(capture.toString().contains("No clusters registered"));
    }
}
