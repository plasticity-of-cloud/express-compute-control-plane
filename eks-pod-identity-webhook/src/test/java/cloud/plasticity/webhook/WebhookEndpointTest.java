package cloud.plasticity.webhook;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class WebhookEndpointTest {

    @InjectMock
    PodIdentityAssociationLookup associationLookup;

    @Test
    void shouldInjectEnvVarsWhenAssociationExists() {
        when(associationLookup.hasAssociation(anyString(), anyString(), anyString())).thenReturn(true);

        AdmissionReview review = buildAdmissionReview("default", "my-sa");

        given()
            .contentType(ContentType.JSON)
            .body(review)
        .when()
            .post("/mutate")
        .then()
            .statusCode(200)
            .body("response.allowed", is(true))
            .body("response.patch", notNullValue()); // base64 JSON patch
    }

    @Test
    void shouldAllowWithoutPatchWhenNoAssociation() {
        when(associationLookup.hasAssociation(anyString(), anyString(), anyString())).thenReturn(false);

        AdmissionReview review = buildAdmissionReview("default", "no-sa");

        given()
            .contentType(ContentType.JSON)
            .body(review)
        .when()
            .post("/mutate")
        .then()
            .statusCode(200)
            .body("response.allowed", is(true))
            // JOSDK returns empty patch array (base64 "[]") when no mutation needed
            .body("response.patch", anyOf(nullValue(), equalTo("W10=")));
    }

    private AdmissionReview buildAdmissionReview(String namespace, String serviceAccount) {
        Pod pod = new PodBuilder()
            .withNewMetadata()
                .withName("test-pod")
                .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
                .withServiceAccountName(serviceAccount)
                .addNewContainer()
                    .withName("app")
                    .withImage("nginx")
                .endContainer()
            .endSpec()
            .build();

        AdmissionRequest request = new AdmissionRequest();
        request.setUid(UUID.randomUUID().toString());
        request.setObject(pod);
        request.setNamespace(namespace);
        request.setOperation("CREATE");

        AdmissionReview review = new AdmissionReview();
        review.setRequest(request);
        return review;
    }
}
