package cloud.plasticity.eksauth;

import cloud.plasticity.eksauth.model.AssumeRoleForPodIdentityRequest;
import cloud.plasticity.eksauth.service.PodIdentityAssociationService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class EksAuthIntegrationTest {

    @InjectMock
    PodIdentityAssociationService podIdentityAssociationService;

    @Test
    @DisplayName("Integration test - invalid audience rejected")
    void testInvalidAudienceRejected() {
        // Arrange - token with wrong audience
        String token = createTestToken("default", "my-sa", "sts.amazonaws.com");

        AssumeRoleForPodIdentityRequest request = new AssumeRoleForPodIdentityRequest();
        request.setClusterName("test-cluster");
        request.setToken(token);

        // Act & Assert - token is rejected (either parse failure or audience mismatch)
        given()
            .contentType(ContentType.JSON)
            .body(request)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("error", equalTo("InvalidRequestException"));
    }

    @Test
    @DisplayName("Integration test - missing required claims")
    void testMissingRequiredClaims() {
        // Arrange - token without namespace claim
        String token = createTestToken(null, "my-sa", "pods.eks.amazonaws.com");

        AssumeRoleForPodIdentityRequest request = new AssumeRoleForPodIdentityRequest();
        request.setClusterName("test-cluster");
        request.setToken(token);

        // Act & Assert
        given()
            .contentType(ContentType.JSON)
            .body(request)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("error", equalTo("InvalidRequestException"));
    }

    @Test
    @DisplayName("Integration test - health check endpoints")
    void testHealthCheckEndpoints() {
        given()
        .when()
            .get("/health/live")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/health/ready")
        .then()
            .statusCode(200);
    }

    /**
     * Creates a simplified test JWT token.
     * Note: This is a simplified token for testing purposes only.
     * In production, tokens are signed by Kubernetes API server.
     */
    private String createTestToken(String namespace, String serviceAccount, String audience) {
        // Create a minimal JWT for testing
        // Header
        String header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        
        // Payload with required claims
        StringBuilder payload = new StringBuilder("{");
        payload.append("\"exp\":").append(Instant.now().plusSeconds(3600).getEpochSecond()).append(",");
        payload.append("\"aud\":\"").append(audience).append("\",");
        if (namespace != null) {
            payload.append("\"kubernetes.io/serviceaccount/namespace\":\"").append(namespace).append("\",");
        }
        if (serviceAccount != null) {
            payload.append("\"kubernetes.io/serviceaccount/service-account.name\":\"").append(serviceAccount).append("\",");
        }
        payload.append("\"sub\":\"system:serviceaccount:").append(namespace != null ? namespace : "").append(":").append(serviceAccount != null ? serviceAccount : "").append("\"");
        payload.append("}");
        
        String payloadEncoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toString().getBytes());
        
        // Signature (empty for testing)
        String signature = "";
        
        return header + "." + payloadEncoded + "." + signature;
    }
}
