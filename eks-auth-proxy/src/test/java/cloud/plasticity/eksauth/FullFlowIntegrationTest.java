package cloud.plasticity.eksauth;

import cloud.plasticity.eksauth.model.AssumeRoleForPodIdentityRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Full end-to-end integration test using real AWS credentials and Kubernetes cluster.
 *
 * Prerequisites:
 *   1. AWS credentials in ~/.aws or environment variables
 *   2. kubeconfig pointing to a cluster with EKS Pod Identity configured
 *   3. A service account token with audience "pods.eks.amazonaws.com":
 *        kubectl create token <SA> -n <NS> --audience pods.eks.amazonaws.com --duration 3600s
 *
 * Run with:
 *   mvn test -Dintegration.full=true \
 *            -Dintegration.cluster=my-cluster \
 *            -Dintegration.namespace=default \
 *            -Dintegration.token=eyJ...
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "integration.full", matches = "true")
class FullFlowIntegrationTest {

    @Test
    void fullFlow_returnsTemporaryCredentials() {
        String clusterName = System.getProperty("integration.cluster");
        String namespace   = System.getProperty("integration.namespace", "default");
        String token       = System.getProperty("integration.token");

        if (clusterName == null || token == null) {
            throw new IllegalStateException(
                "Required: -Dintegration.cluster=<name> -Dintegration.token=<jwt>");
        }

        AssumeRoleForPodIdentityRequest request = new AssumeRoleForPodIdentityRequest();
        request.setClusterName(clusterName);
        request.setToken(token);

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
            .body("Credentials.Expiration",      notNullValue())
            .body("Subject.Namespace",           equalTo(namespace));
    }
}
