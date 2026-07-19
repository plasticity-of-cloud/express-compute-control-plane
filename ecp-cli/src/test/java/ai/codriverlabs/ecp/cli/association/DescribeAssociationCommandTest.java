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
class DescribeAssociationCommandTest {

    @Mock EcpApiClient apiClient;

    DescribeAssociationCommand cmd;

    @BeforeEach
    void setUp() {
        cmd = new DescribeAssociationCommand();
        cmd.apiClient = apiClient;
        cmd.clusterName = "test-cluster";
        cmd.associationId = "assoc-abc";
    }

    @Test
    void run_callsCorrectEndpoint() {
        when(apiClient.get("/clusters/test-cluster/workload-identities/assoc-abc"))
            .thenReturn("{\"associationId\":\"assoc-abc\",\"clusterName\":\"test-cluster\",\"namespace\":\"default\",\"serviceAccount\":\"my-sa\",\"roleArn\":\"arn:aws:iam::123:role/r\",\"createdAt\":\"2025-01-01\"}");

        cmd.run();

        verify(apiClient).get("/clusters/test-cluster/workload-identities/assoc-abc");
    }

    @Test
    void run_printsFormattedOutput() {
        when(apiClient.get(anyString()))
            .thenReturn("{\"associationId\":\"assoc-abc\",\"clusterName\":\"test-cluster\",\"namespace\":\"default\",\"serviceAccount\":\"my-sa\",\"roleArn\":\"arn:aws:iam::123:role/r\",\"createdAt\":\"2025-01-01\"}");

        PrintStream original = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture));
        try {
            cmd.run();
        } finally {
            System.setOut(original);
        }

        String output = capture.toString();
        assertTrue(output.contains("assoc-abc"));
        assertTrue(output.contains("test-cluster"));
        assertTrue(output.contains("default"));
        assertTrue(output.contains("my-sa"));
        assertTrue(output.contains("arn:aws:iam::123:role/r"));
    }

    @Test
    void run_handlesMissingFields() {
        when(apiClient.get(anyString()))
            .thenReturn("{\"associationId\":\"assoc-abc\"}");

        PrintStream original = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture));
        try {
            cmd.run();
        } finally {
            System.setOut(original);
        }

        String output = capture.toString();
        assertTrue(output.contains("assoc-abc"));
        assertTrue(output.contains("-")); // missing fields
    }
}
