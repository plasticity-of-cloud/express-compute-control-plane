package com.plcloud.eksauth;

import com.plcloud.eksauth.model.AssumeRoleForPodIdentityRequest;
import io.fabric8.kubernetes.api.model.authentication.TokenReviewBuilder;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.*;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.CreatePolicyRequest;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.DeletePolicyRequest;
import software.amazon.awssdk.services.iam.model.DeleteRoleRequest;
import software.amazon.awssdk.services.iam.model.DetachRolePolicyRequest;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Self-contained integration test that provisions real AWS resources, uses a
 * fabric8 mock Kubernetes server for TokenReview, and validates the full proxy flow.
 *
 * Prerequisites:
 *   - AWS credentials in ~/.aws or environment (needs iam:*, eks:*, sts:AssumeRole)
 *   - An existing EKS cluster with the eks-pod-identity-agent add-on installed
 *
 * Run with:
 *   mvn test -Dintegration.aws=true -Dintegration.cluster=my-cluster
 */
@QuarkusTest
@WithKubernetesTestServer
@EnabledIfSystemProperty(named = "integration.aws", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AwsProvisionedIntegrationTest {

    static final String SUFFIX       = UUID.randomUUID().toString().substring(0, 8);
    static final String ROLE_NAME    = "eks-proxy-test-" + SUFFIX;
    static final String POLICY_NAME  = "eks-proxy-ecr-test-" + SUFFIX;
    static final String NAMESPACE    = "integration-test";
    static final String SERVICE_ACCT = "test-sa";
    static final String TEST_TOKEN   = "integration-test-token-" + SUFFIX;

    static final IamClient iam = IamClient.builder().build();
    static final EksClient eks = EksClient.builder().build();
    static final software.amazon.awssdk.services.sts.StsClient sts =
        software.amazon.awssdk.services.sts.StsClient.builder().build();

    static String clusterName;
    static String roleArn;
    static String policyArn;
    static String associationId;

    @KubernetesTestServer
    KubernetesServer mockServer;

    @BeforeAll
    static void provisionAws() {
        clusterName = System.getProperty("integration.cluster");
        if (clusterName == null) throw new IllegalStateException("Required: -Dintegration.cluster=<name>");

        // 1. IAM role — trust both EKS Pod Identity and the current caller (for local testing)
        String callerArn = sts.getCallerIdentity().arn();
        roleArn = iam.createRole(CreateRoleRequest.builder()
            .roleName(ROLE_NAME)
            .assumeRolePolicyDocument(String.format("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"Service":"pods.eks.amazonaws.com"},
                   "Action":["sts:AssumeRole","sts:TagSession"]},
                  {"Effect":"Allow","Principal":{"AWS":"%s"},
                   "Action":["sts:AssumeRole","sts:TagSession"]}
                ]}""", callerArn))
            .build()
        ).role().arn();

        // 2. Inline ECR read policy
        policyArn = iam.createPolicy(CreatePolicyRequest.builder()
            .policyName(POLICY_NAME)
            .policyDocument("""
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow",
                  "Action":["ecr:GetAuthorizationToken","ecr:BatchGetImage",
                            "ecr:GetDownloadUrlForLayer","ecr:BatchCheckLayerAvailability"],
                  "Resource":"*"
                }]}""")
            .build()
        ).policy().arn();

        iam.attachRolePolicy(AttachRolePolicyRequest.builder()
            .roleName(ROLE_NAME).policyArn(policyArn).build());

        // IAM is eventually consistent — wait for trust policy to propagate
        try { Thread.sleep(10_000); } catch (InterruptedException ignored) {}

        // 3. Clean up any stale associations for this namespace/SA, then create fresh one
        eks.listPodIdentityAssociations(ListPodIdentityAssociationsRequest.builder()
            .clusterName(clusterName).namespace(NAMESPACE).serviceAccount(SERVICE_ACCT).build()
        ).associations().forEach(a -> eks.deletePodIdentityAssociation(
            DeletePodIdentityAssociationRequest.builder()
                .clusterName(clusterName).associationId(a.associationId()).build()));

        associationId = eks.createPodIdentityAssociation(CreatePodIdentityAssociationRequest.builder()
            .clusterName(clusterName)
            .namespace(NAMESPACE)
            .serviceAccount(SERVICE_ACCT)
            .roleArn(roleArn)
            .build()
        ).association().associationId();
    }

    @AfterAll
    static void cleanupAws() {
        if (associationId != null) {
            try { eks.deletePodIdentityAssociation(DeletePodIdentityAssociationRequest.builder()
                .clusterName(clusterName).associationId(associationId).build()); }
            catch (Exception ignored) {}
        }
        if (policyArn != null && roleArn != null) {
            try { iam.detachRolePolicy(DetachRolePolicyRequest.builder()
                .roleName(ROLE_NAME).policyArn(policyArn).build()); }
            catch (Exception ignored) {}
        }
        if (policyArn != null) {
            try { iam.deletePolicy(DeletePolicyRequest.builder().policyArn(policyArn).build()); }
            catch (Exception ignored) {}
        }
        if (roleArn != null) {
            try { iam.deleteRole(DeleteRoleRequest.builder().roleName(ROLE_NAME).build()); }
            catch (Exception ignored) {}
        }
    }

    @BeforeEach
    void setupTokenReview() {
        // Mock Kubernetes TokenReview: accept our test token, return the test service account identity
        var tokenReviewResponse = new TokenReviewBuilder()
            .withNewStatus()
                .withAuthenticated(true)
                .withNewUser()
                    .withUsername("system:serviceaccount:" + NAMESPACE + ":" + SERVICE_ACCT)
                    .withUid("test-uid-" + SUFFIX)
                .endUser()
            .endStatus()
            .build();

        mockServer.expect().post()
            .withPath("/apis/authentication.k8s.io/v1/tokenreviews")
            .andReturn(200, tokenReviewResponse)
            .always();
    }

    @Test
    void fullFlow_returnsRealTemporaryCredentials() {
        AssumeRoleForPodIdentityRequest request = new AssumeRoleForPodIdentityRequest();
        request.setClusterName(clusterName);
        request.setToken(TEST_TOKEN);

        given()
            .contentType(ContentType.JSON)
            .body(request)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Credentials.AccessKeyId",     notNullValue())
            .body("Credentials.SecretAccessKey", notNullValue())
            .body("Credentials.SessionToken",    notNullValue())
            .body("Subject.Namespace",           equalTo(NAMESPACE))
            .body("Subject.ServiceAccount",      equalTo(SERVICE_ACCT));
    }
}
