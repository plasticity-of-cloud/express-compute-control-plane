package com.plcloud.eksauth;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
public class EksAuthResourceTest {

    @Test
    public void testHealthEndpoint() {
        given()
          .when().get("/health/live")
          .then()
             .statusCode(200)
             .body("status", is("UP"));
    }

    @Test
    public void testAssumeRoleEndpoint() {
        String requestBody = """
            {
                "ClusterName": "test-cluster",
                "Token": "eyJhbGciOiJSUzI1NiIsImtpZCI6InRlc3QifQ.eyJpc3MiOiJrdWJlcm5ldGVzL3NlcnZpY2VhY2NvdW50Iiwia3ViZXJuZXRlcy5pby9zZXJ2aWNlYWNjb3VudC9uYW1lc3BhY2UiOiJkZWZhdWx0Iiwia3ViZXJuZXRlcy5pby9zZXJ2aWNlYWNjb3VudC9zZWNyZXQubmFtZSI6InRlc3QtdG9rZW4iLCJrdWJlcm5ldGVzLmlvL3NlcnZpY2VhY2NvdW50L3NlcnZpY2UtYWNjb3VudC5uYW1lIjoidGVzdC1zYSIsInN1YiI6InN5c3RlbTpzZXJ2aWNlYWNjb3VudDpkZWZhdWx0OnRlc3Qtc2EifQ.test"
            }
            """;
        
        given()
          .contentType(ContentType.JSON)
          .body(requestBody)
          .when().post("/")
          .then()
             .statusCode(200);
    }
}
