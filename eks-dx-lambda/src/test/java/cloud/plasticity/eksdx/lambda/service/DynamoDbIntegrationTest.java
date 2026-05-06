package cloud.plasticity.eksdx.lambda.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.GetRoleRequest;
import software.amazon.awssdk.services.iam.model.GetRoleResponse;
import software.amazon.awssdk.services.iam.model.Role;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests using DynamoDB Local.
 * Run with: mvn test -Dtest=DynamoDbIntegrationTest -Dintegration.dynamodb=true
 *
 * Requires DynamoDB Local running on port 18000:
 *   docker run -d -p 18000:8000 public.ecr.aws/aws-dynamodb-local/aws-dynamodb-local:latest
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbIntegrationTest {

    static final String ENDPOINT = System.getProperty("dynamodb.endpoint", "http://localhost:18000");

    static DynamoDbClient client;
    static DynamoDbClusterService clusterService;
    static DynamoDbAssociationService associationService;

    @BeforeAll
    static void setUp() {
        String flag = System.getProperty("integration.dynamodb");
        Assumptions.assumeTrue("true".equals(flag),
            "Skipped: pass -Dintegration.dynamodb=true to run");

        client = DynamoDbClient.builder()
            .endpointOverride(URI.create(ENDPOINT))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .build();

        // Create tables (ignore if already exist)
        createTableIfNotExists("it-clusters",
            List.of(AttributeDefinition.builder().attributeName("clusterName").attributeType(ScalarAttributeType.S).build()),
            List.of(KeySchemaElement.builder().attributeName("clusterName").keyType(KeyType.HASH).build()));

        createTableIfNotExists("it-associations",
            List.of(
                AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build()),
            List.of(
                KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build()));

        clusterService = new DynamoDbClusterService();
        clusterService.dynamoDb = client;
        clusterService.tableName = "it-clusters";

        IamClient iamClient = mock(IamClient.class);
        when(iamClient.getRole(any(GetRoleRequest.class))).thenReturn(
            GetRoleResponse.builder()
                .role(Role.builder()
                    .roleName("test")
                    .arn("arn:aws:iam::123456789012:role/test")
                    .assumeRolePolicyDocument("{\"Statement\":[{\"Action\":\"sts:AssumeRole\"}]}")
                    .path("/")
                    .createDate(java.time.Instant.now())
                    .build())
                .build());

        associationService = new DynamoDbAssociationService();
        associationService.dynamoDb = client;
        associationService.tableName = "it-associations";
        associationService.iamClient = iamClient;
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            try { client.deleteTable(DeleteTableRequest.builder().tableName("it-clusters").build()); } catch (Exception ignored) {}
            try { client.deleteTable(DeleteTableRequest.builder().tableName("it-associations").build()); } catch (Exception ignored) {}
            client.close();
        }
    }

    // --- Cluster lifecycle ---

    @Test @Order(1)
    void registerCluster() {
        Map<String, String> result = clusterService.registerCluster(
            "it-k3s", "https://oidc.example.com", "{\"keys\":[{\"kty\":\"RSA\"}]}");
        assertEquals("it-k3s", result.get("clusterName"));
    }

    @Test @Order(2)
    void describeCluster() {
        Map<String, String> result = clusterService.describeCluster("it-k3s");
        assertEquals("https://oidc.example.com", result.get("issuer"));
    }

    @Test @Order(3)
    void getJwks() {
        assertEquals("{\"keys\":[{\"kty\":\"RSA\"}]}", clusterService.getJwks("it-k3s"));
    }

    @Test @Order(4)
    void getIssuer() {
        assertEquals("https://oidc.example.com", clusterService.getIssuer("it-k3s"));
    }

    @Test @Order(5)
    void listClusters() {
        assertTrue(clusterService.listClusters().stream()
            .anyMatch(c -> "it-k3s".equals(c.get("clusterName"))));
    }

    @Test @Order(6)
    void registerCluster_rejectsDuplicate() {
        assertThrows(IllegalStateException.class,
            () -> clusterService.registerCluster("it-k3s", "https://other.com", "{\"keys\":[]}"));
    }

    @Test @Order(7)
    void updateJwks() {
        clusterService.updateJwks("it-k3s", "{\"keys\":[{\"kty\":\"EC\"}]}");
        assertEquals("{\"keys\":[{\"kty\":\"EC\"}]}", clusterService.getJwks("it-k3s"));
    }

    // --- Association lifecycle ---

    @Test @Order(10)
    void createAssociation() {
        Map<String, String> result = associationService.createAssociation(
            "it-k3s", "default", "my-app", "arn:aws:iam::123456789012:role/test");
        assertTrue(result.get("associationId").startsWith("assoc-"));
    }

    @Test @Order(11)
    void getRoleArn() {
        assertEquals("arn:aws:iam::123456789012:role/test",
            associationService.getRoleArn("it-k3s", "default", "my-app"));
    }

    @Test @Order(12)
    void getRoleArn_missing() {
        assertNull(associationService.getRoleArn("it-k3s", "default", "missing"));
    }

    @Test @Order(13)
    void listAssociations() {
        assertEquals(1, associationService.listAssociations("it-k3s", null, null).size());
    }

    @Test @Order(14)
    void listAssociations_filterByNamespace() {
        assertEquals(1, associationService.listAssociations("it-k3s", "default", null).size());
        assertTrue(associationService.listAssociations("it-k3s", "kube-system", null).isEmpty());
    }

    @Test @Order(15)
    void createAssociation_rejectsDuplicate() {
        assertThrows(IllegalStateException.class,
            () -> associationService.createAssociation("it-k3s", "default", "my-app", "arn:other"));
    }

    @Test @Order(16)
    void describeAssociation() {
        String id = associationService.getAssociationId("it-k3s", "default", "my-app");
        assertNotNull(associationService.describeAssociation("it-k3s", id));
    }

    @Test @Order(17)
    void deleteAssociation() {
        String id = associationService.getAssociationId("it-k3s", "default", "my-app");
        associationService.deleteAssociation("it-k3s", id);
        assertNull(associationService.getRoleArn("it-k3s", "default", "my-app"));
    }

    // --- Cluster cleanup ---

    @Test @Order(20)
    void deregisterCluster() {
        clusterService.deregisterCluster("it-k3s");
        assertThrows(IllegalArgumentException.class, () -> clusterService.describeCluster("it-k3s"));
    }

    // --- helpers ---

    private static void createTableIfNotExists(String tableName,
            List<AttributeDefinition> attrs, List<KeySchemaElement> keys) {
        try {
            client.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .attributeDefinitions(attrs)
                .keySchema(keys)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
        } catch (ResourceInUseException e) {
            // Table already exists — clean it
            client.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            client.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .attributeDefinitions(attrs)
                .keySchema(keys)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
        }
    }
}
