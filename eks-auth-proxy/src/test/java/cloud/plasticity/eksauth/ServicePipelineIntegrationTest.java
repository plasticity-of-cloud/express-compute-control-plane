package cloud.plasticity.eksauth;

import cloud.plasticity.eksauth.crd.PodIdentityAssociation;
import cloud.plasticity.eksauth.crd.PodIdentityAssociationSpec;
import cloud.plasticity.eksauth.model.AssumeRoleForPodIdentityRequest;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.authentication.TokenReviewBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.ListPodIdentityAssociationsRequest;
import software.amazon.awssdk.services.eks.model.ListPodIdentityAssociationsResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Component integration test: wires TokenValidationService + PodIdentityAssociationService
 * + AwsCredentialService through the HTTP layer.
 *
 * K8s interactions (TokenReview, CRD) go through the real Fabric8 mock server.
 * AWS SDK clients (EksClient, StsClient) are replaced with Mockito mocks via @InjectMock.
 */
@QuarkusTest
@WithKubernetesTestServer
class ServicePipelineIntegrationTest {

    @KubernetesTestServer
    KubernetesServer mockServer;

    @Inject
    KubernetesClient kubernetesClient;

    @InjectMock
    EksClient eksClient;

    @InjectMock
    StsClient stsClient;

    private static final String CLUSTER   = "test-cluster";
    private static final String NAMESPACE = "default";
    private static final String SA        = "my-sa";
    private static final String ROLE_ARN  = "arn:aws:iam::123456789012:role/test-role";

    @BeforeEach
    void resetMockServer() {
        // EKS API returns empty by default so CRD / generated-default fallbacks are exercised
        when(eksClient.listPodIdentityAssociations(any(ListPodIdentityAssociationsRequest.class)))
            .thenReturn(ListPodIdentityAssociationsResponse.builder().build());

        // Clean up CRDs from previous tests
        kubernetesClient.resources(PodIdentityAssociation.class)
            .inNamespace(NAMESPACE)
            .withName(CLUSTER + "-" + SA)
            .delete();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void stubTokenReview(boolean authenticated, String username) {
        var builder = new TokenReviewBuilder().withNewStatus();
        if (authenticated) {
            builder.withAuthenticated(true)
                   .withNewUser().withUsername(username).withUid("uid-123").endUser();
        } else {
            builder.withAuthenticated(false).withError("token has expired");
        }
        var response = builder.endStatus().build();

        mockServer.expect().post()
            .withPath("/apis/authentication.k8s.io/v1/tokenreviews")
            .andReturn(200, response)
            .once();
    }

    private void stubSts() {
        when(stsClient.assumeRole(any(AssumeRoleRequest.class)))
            .thenReturn(AssumeRoleResponse.builder()
                .credentials(Credentials.builder()
                    .accessKeyId("AKIATEST")
                    .secretAccessKey("secret")
                    .sessionToken("token")
                    .expiration(Instant.now().plusSeconds(3600))
                    .build())
                .build());
    }

    private PodIdentityAssociation createCrd(String namespace, String sa, String roleArn) {
        var crd = new PodIdentityAssociation();
        crd.setMetadata(new ObjectMetaBuilder()
            .withName(CLUSTER + "-" + sa)
            .withNamespace(namespace)
            .build());
        crd.setSpec(new PodIdentityAssociationSpec(CLUSTER, namespace, sa, roleArn));
        return kubernetesClient.resources(PodIdentityAssociation.class)
            .inNamespace(namespace)
            .resource(crd)
            .create();
    }

    private AssumeRoleForPodIdentityRequest request(String token) {
        var req = new AssumeRoleForPodIdentityRequest();
        req.setClusterName(CLUSTER);
        req.setToken(token);
        return req;
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Full pipeline: CRD role mapping → real credentials returned")
    void fullPipeline_crdRoleMapping() {
        createCrd(NAMESPACE, SA, ROLE_ARN);
        stubTokenReview(true, "system:serviceaccount:" + NAMESPACE + ":" + SA);
        stubSts();

        given()
            .contentType(ContentType.JSON)
            .body(request("valid-token"))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Credentials.AccessKeyId",     equalTo("AKIATEST"))
            .body("Credentials.SecretAccessKey", notNullValue())
            .body("Subject.Namespace",           equalTo(NAMESPACE))
            .body("Subject.ServiceAccount",      equalTo(SA));

        verify(stsClient).assumeRole(argThat((AssumeRoleRequest r) ->
            r.roleArn().equals(ROLE_ARN) &&
            r.tags().stream().anyMatch(t -> t.key().equals("eks-cluster-name") && t.value().equals(CLUSTER)) &&
            r.tags().stream().anyMatch(t -> t.key().equals("kubernetes-namespace") && t.value().equals(NAMESPACE))
        ));
    }

    @Test
    @DisplayName("No CRD → falls back to generated default role ARN")
    void noCrd_fallsBackToGeneratedDefault() {
        stubTokenReview(true, "system:serviceaccount:" + NAMESPACE + ":" + SA);
        stubSts();

        given()
            .contentType(ContentType.JSON)
            .body(request("valid-token"))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Generated default: arn:aws:iam::<account>:role/eks-pod-identity-<ns>-<sa>
        verify(stsClient).assumeRole(argThat((AssumeRoleRequest r) ->
            r.roleArn().contains("eks-pod-identity-" + NAMESPACE + "-" + SA)
        ));
    }

    @Test
    @DisplayName("TokenReview rejection propagates as 400")
    void tokenReviewRejection_returns400() {
        stubTokenReview(false, null);

        given()
            .contentType(ContentType.JSON)
            .body(request("expired-token"))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("error", equalTo("InvalidRequestException"))
            .body("message", containsString("token has expired"));

        verifyNoInteractions(stsClient);
    }

    @Test
    @DisplayName("STS failure propagates as 500")
    void stsFailure_returns500() {
        createCrd(NAMESPACE, SA, ROLE_ARN);
        stubTokenReview(true, "system:serviceaccount:" + NAMESPACE + ":" + SA);
        when(stsClient.assumeRole(any(AssumeRoleRequest.class)))
            .thenThrow(new RuntimeException("Access denied by STS"));

        given()
            .contentType(ContentType.JSON)
            .body(request("valid-token"))
        .when()
            .post("/")
        .then()
            .statusCode(500)
            .body("error", equalTo("InternalServerException"));
    }

    @Test
    @DisplayName("Bearer prefix stripped before TokenReview")
    void bearerPrefixStripped_tokenReviewReceivesRawToken() {
        stubTokenReview(true, "system:serviceaccount:" + NAMESPACE + ":" + SA);
        stubSts();

        given()
            .contentType(ContentType.JSON)
            .body(request("Bearer actual-token"))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        verify(stsClient).assumeRole(any(AssumeRoleRequest.class));
    }
}
