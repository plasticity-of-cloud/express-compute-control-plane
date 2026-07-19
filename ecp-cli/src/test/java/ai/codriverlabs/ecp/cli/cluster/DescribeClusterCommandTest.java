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
class DescribeClusterCommandTest {

    @Mock EcpApiClient apiClient;

    DescribeClusterCommand cmd;

    @BeforeEach
    void setUp() {
        cmd = new DescribeClusterCommand();
        cmd.apiClient = apiClient;
        cmd.name = "test-cluster";
    }

    @Test
    void run_callsCorrectEndpoint() {
        when(apiClient.get("/clusters/test-cluster"))
            .thenReturn("{\"clusterName\":\"test-cluster\",\"issuer\":\"https://oidc.example.com\"}");

        cmd.run();

        verify(apiClient).get("/clusters/test-cluster");
    }

    @Test
    void run_printsFormattedOutput() {
        when(apiClient.get("/clusters/test-cluster"))
            .thenReturn("{\"clusterName\":\"test-cluster\",\"issuer\":\"https://oidc.example.com\",\"createdAt\":\"2025-01-01\",\"updatedAt\":\"2025-01-02\"}");

        PrintStream original = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture));
        try {
            cmd.run();
        } finally {
            System.setOut(original);
        }

        String output = capture.toString();
        assertTrue(output.contains("test-cluster"));
        assertTrue(output.contains("https://oidc.example.com"));
        assertTrue(output.contains("2025-01-01"));
        assertTrue(output.contains("2025-01-02"));
    }

    @Test
    void run_handlesMissingFields() {
        when(apiClient.get("/clusters/test-cluster"))
            .thenReturn("{\"clusterName\":\"test-cluster\"}");

        PrintStream original = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture));
        try {
            cmd.run();
        } finally {
            System.setOut(original);
        }

        String output = capture.toString();
        assertTrue(output.contains("test-cluster"));
        assertTrue(output.contains("-")); // missing fields show as "-"
    }
}
