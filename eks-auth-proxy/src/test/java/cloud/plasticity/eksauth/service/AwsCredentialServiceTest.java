package cloud.plasticity.eksauth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AwsCredentialServiceTest {

    @Mock
    StsClient stsClient;

    AwsCredentialService awsCredentialService;

    @BeforeEach
    void setUp() {
        awsCredentialService = new AwsCredentialService(stsClient);
        awsCredentialService.sessionDuration = Duration.ofHours(1);
        awsCredentialService.accountId = Optional.of("123456789012");
        awsCredentialService.region = Optional.of("us-east-1");
    }

    @Test
    @DisplayName("Should assume role with session tags")
    void testAssumeRoleWithSessionTags() {
        // Arrange
        String roleArn = "arn:aws:iam::123456789012:role/test-role";
        String sessionName = "eks-pod-identity-default-my-sa-abc123";
        String clusterName = "my-cluster";
        Map<String, String> sessionTags = Map.of(
            "kubernetes-namespace", "default",
            "kubernetes-service-account", "my-sa",
            "kubernetes-pod-name", "my-pod",
            "kubernetes-pod-uid", "pod-uid-123"
        );

        Credentials credentials = Credentials.builder()
            .accessKeyId("AKIAIOSFODNN7EXAMPLE")
            .secretAccessKey("secret")
            .sessionToken("session-token")
            .expiration(Instant.now().plusSeconds(3600))
            .build();

        AssumeRoleResponse mockResponse = AssumeRoleResponse.builder()
            .credentials(credentials)
            .build();
        
        when(stsClient.assumeRole(any(AssumeRoleRequest.class))).thenReturn(mockResponse);

        // Act
        Credentials result = awsCredentialService.assumeRole(roleArn, sessionName, clusterName, sessionTags);

        // Assert
        assertNotNull(result);
        assertEquals("AKIAIOSFODNN7EXAMPLE", result.accessKeyId());
        
        verify(stsClient).assumeRole(argThat((AssumeRoleRequest request) -> {
            // Verify session tags are present
            return request.tags().stream()
                .anyMatch(tag -> tag.key().equals("eks-cluster-name") && tag.value().equals("my-cluster")) &&
                request.tags().stream()
                .anyMatch(tag -> tag.key().equals("eks-cluster-arn") && tag.value().contains("my-cluster")) &&
                request.tags().stream()
                .anyMatch(tag -> tag.key().equals("kubernetes-namespace") && tag.value().equals("default")) &&
                request.tags().stream()
                .anyMatch(tag -> tag.key().equals("kubernetes-service-account") && tag.value().equals("my-sa"));
        }));
    }

    @Test
    @DisplayName("Should generate correct cluster ARN")
    void testClusterArnGeneration() {
        // Arrange
        String roleArn = "arn:aws:iam::123456789012:role/test-role";
        String sessionName = "test-session";
        String clusterName = "prod-cluster";
        Map<String, String> sessionTags = Map.of(
            "kubernetes-namespace", "production",
            "kubernetes-service-account", "app-sa",
            "kubernetes-pod-name", "",
            "kubernetes-pod-uid", ""
        );

        Credentials credentials = Credentials.builder()
            .accessKeyId("AKIAIOSFODNN7EXAMPLE")
            .secretAccessKey("secret")
            .sessionToken("session-token")
            .expiration(Instant.now().plusSeconds(3600))
            .build();

        AssumeRoleResponse mockResponse = AssumeRoleResponse.builder()
            .credentials(credentials)
            .build();
        
        when(stsClient.assumeRole(any(AssumeRoleRequest.class))).thenReturn(mockResponse);

        // Act
        awsCredentialService.assumeRole(roleArn, sessionName, clusterName, sessionTags);

        // Assert
        verify(stsClient).assumeRole(argThat((AssumeRoleRequest request) -> 
            request.tags().stream()
                .anyMatch(tag -> tag.key().equals("eks-cluster-arn") && 
                    tag.value().equals("arn:aws:eks:us-east-1:123456789012:cluster/prod-cluster"))
        ));
    }

    @Test
    @DisplayName("Should generate session name correctly")
    void testGenerateSessionName() {
        // Act
        String sessionName = awsCredentialService.generateSessionName("default", "my-sa");

        // Assert
        assertTrue(sessionName.startsWith("eks-pod-identity-default-my-sa-"));
        assertTrue(sessionName.length() > "eks-pod-identity-default-my-sa-".length());
    }

    @Test
    @DisplayName("Should handle empty pod tags gracefully")
    void testEmptyPodTags() {
        // Arrange
        String roleArn = "arn:aws:iam::123456789012:role/test-role";
        String sessionName = "test-session";
        String clusterName = "my-cluster";
        Map<String, String> sessionTags = Map.of(
            "kubernetes-namespace", "default",
            "kubernetes-service-account", "my-sa",
            "kubernetes-pod-name", "",
            "kubernetes-pod-uid", ""
        );

        Credentials credentials = Credentials.builder()
            .accessKeyId("AKIAIOSFODNN7EXAMPLE")
            .secretAccessKey("secret")
            .sessionToken("session-token")
            .expiration(Instant.now().plusSeconds(3600))
            .build();

        AssumeRoleResponse mockResponse = AssumeRoleResponse.builder()
            .credentials(credentials)
            .build();
        
        when(stsClient.assumeRole(any(AssumeRoleRequest.class))).thenReturn(mockResponse);

        // Act
        Credentials result = awsCredentialService.assumeRole(roleArn, sessionName, clusterName, sessionTags);

        // Assert - empty pod tags should not be included
        verify(stsClient).assumeRole(argThat((AssumeRoleRequest request) ->
            request.tags().stream()
                .filter(tag -> tag.key().startsWith("kubernetes-pod"))
                .allMatch(tag -> !tag.value().isEmpty())
        ));
    }

    @Test
    @DisplayName("Should throw exception on STS failure")
    void testStsFailure() {
        // Arrange
        String roleArn = "arn:aws:iam::123456789012:role/test-role";
        String sessionName = "test-session";
        String clusterName = "my-cluster";
        Map<String, String> sessionTags = Map.of(
            "kubernetes-namespace", "default",
            "kubernetes-service-account", "my-sa",
            "kubernetes-pod-name", "pod",
            "kubernetes-pod-uid", "uid"
        );

        when(stsClient.assumeRole(any(AssumeRoleRequest.class)))
            .thenThrow(new RuntimeException("Access denied"));

        // Act & Assert
        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> awsCredentialService.assumeRole(roleArn, sessionName, clusterName, sessionTags)
        );
        assertTrue(exception.getMessage().contains("Failed to assume role"));
    }
}
