package cloud.plasticity.eksauth;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

@QuarkusTest
public class EksAuthResourceTest {

    @Test
    @DisplayName("Health check endpoints should return UP status")
    public void testHealthEndpoints() {
        given()
          .when().get("/health/live")
          .then()
             .statusCode(200)
             .body("status", is("UP"));

        given()
          .when().get("/health/ready")
          .then()
             .statusCode(200)
             .body("status", is("UP"));
    }

    @Test
    @DisplayName("Metrics endpoint should be accessible")
    public void testMetricsEndpoint() {
        given()
          .when().get("/metrics")
          .then()
             .statusCode(200);
    }

    @Test
    @DisplayName("Should reject request with missing ClusterName")
    public void testMissingClusterName() {
        String requestBody = """
            {
                "Token": "eyJhbGciOiJSUzI1NiIsImtpZCI6InRlc3QifQ.test"
            }
            """;
        
        given()
          .contentType(ContentType.JSON)
          .body(requestBody)
          .when().post("/")
          .then()
             .statusCode(400)
             .body("error", equalTo("InvalidRequestException"));
    }

    @Test
    @DisplayName("Should reject request with missing Token")
    public void testMissingToken() {
        String requestBody = """
            {
                "ClusterName": "test-cluster"
            }
            """;
        
        given()
          .contentType(ContentType.JSON)
          .body(requestBody)
          .when().post("/")
          .then()
             .statusCode(400)
             .body("error", equalTo("InvalidRequestException"));
    }

    @Test
    @DisplayName("Should reject request with empty body")
    public void testEmptyRequestBody() {
        given()
          .contentType(ContentType.JSON)
          .body("{}")
          .when().post("/")
          .then()
             .statusCode(400)
             .body("error", equalTo("InvalidRequestException"));
    }

    @Test
    @DisplayName("Should reject request with invalid JSON")
    public void testInvalidJson() {
        given()
          .contentType(ContentType.JSON)
          .body("{invalid json")
          .when().post("/")
          .then()
             .statusCode(400);
    }

    @Test
    @DisplayName("Should reject request with wrong content type")
    public void testWrongContentType() {
        given()
          .contentType(ContentType.TEXT)
          .body("test")
          .when().post("/")
          .then()
             .statusCode(415);
    }

    @Test
    @DisplayName("Should handle GET request to root endpoint")
    public void testGetRequestToRoot() {
        given()
          .when().get("/")
          .then()
             .statusCode(405); // Method Not Allowed
    }

    @Test
    @DisplayName("Should handle PUT request to root endpoint")
    public void testPutRequestToRoot() {
        given()
          .contentType(ContentType.JSON)
          .body("{}")
          .when().put("/")
          .then()
             .statusCode(405); // Method Not Allowed
    }

    @Test
    @DisplayName("Should handle DELETE request to root endpoint")
    public void testDeleteRequestToRoot() {
        given()
          .when().delete("/")
          .then()
             .statusCode(405); // Method Not Allowed
    }
}
