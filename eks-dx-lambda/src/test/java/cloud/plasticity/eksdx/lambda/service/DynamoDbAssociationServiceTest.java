package cloud.plasticity.eksdx.lambda.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.GetRoleRequest;
import software.amazon.awssdk.services.iam.model.GetRoleResponse;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.Role;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class DynamoDbAssociationServiceTest {

    @Mock
    DynamoDbClient dynamoDb;

    @Mock
    IamClient iamClient;

    DynamoDbAssociationService service;

    @BeforeEach
    void setUp() {
        service = new DynamoDbAssociationService();
        service.dynamoDb = dynamoDb;
        service.iamClient = iamClient;
        service.tableName = "test-associations";
    }

    // --- getRoleArn ---

    @Test
    void getRoleArn_returnsArn_whenAssociationExists() {
        mockGetItem(Map.of(
            "PK", AttributeValue.fromS("CLUSTER#test-cluster"),
            "SK", AttributeValue.fromS("default#my-sa"),
            "roleArn", AttributeValue.fromS("arn:aws:iam::123456789012:role/test-role")
        ));

        assertEquals("arn:aws:iam::123456789012:role/test-role",
            service.getRoleArn("test-cluster", "default", "my-sa"));
    }

    @Test
    void getRoleArn_returnsNull_whenNotFound() {
        mockGetItemEmpty();

        assertNull(service.getRoleArn("test-cluster", "default", "missing-sa"));
    }

    @Test
    void getRoleArn_usesCorrectKey() {
        mockGetItemEmpty();

        service.getRoleArn("my-cluster", "kube-system", "webhook-sa");

        ArgumentCaptor<GetItemRequest> captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamoDb).getItem(captor.capture());
        assertEquals("CLUSTER#my-cluster", captor.getValue().key().get("PK").s());
        assertEquals("kube-system#webhook-sa", captor.getValue().key().get("SK").s());
    }

    // --- getAssociationId ---

    @Test
    void getAssociationId_returnsId_whenExists() {
        mockGetItem(Map.of(
            "PK", AttributeValue.fromS("CLUSTER#test-cluster"),
            "SK", AttributeValue.fromS("default#my-sa"),
            "associationId", AttributeValue.fromS("assoc-abc123")
        ));

        assertEquals("assoc-abc123",
            service.getAssociationId("test-cluster", "default", "my-sa"));
    }

    @Test
    void getAssociationId_returnsNull_whenNotFound() {
        mockGetItemEmpty();

        assertNull(service.getAssociationId("test-cluster", "default", "missing-sa"));
    }

    // --- createAssociation ---

    @Test
    void createAssociation_succeeds_whenNoExistingAssociation() {
        mockGetItemEmpty();
        mockRoleExists();
        when(dynamoDb.putItem(any(PutItemRequest.class)))
            .thenReturn(PutItemResponse.builder().build());

        Map<String, String> result = service.createAssociation("test-cluster", "default",
            "my-sa", "arn:aws:iam::123456789012:role/test-role");

        assertTrue(result.get("associationId").startsWith("assoc-"));
        assertEquals("test-cluster", result.get("clusterName"));
        assertEquals("default", result.get("namespace"));
        assertEquals("my-sa", result.get("serviceAccount"));
        assertEquals("arn:aws:iam::123456789012:role/test-role", result.get("roleArn"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void createAssociation_storesCorrectItem() {
        mockGetItemEmpty();
        mockRoleExists();
        when(dynamoDb.putItem(any(PutItemRequest.class)))
            .thenReturn(PutItemResponse.builder().build());

        service.createAssociation("test-cluster", "default",
            "my-sa", "arn:aws:iam::123456789012:role/test-role");

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        Map<String, AttributeValue> item = captor.getValue().item();
        assertEquals("CLUSTER#test-cluster", item.get("PK").s());
        assertEquals("default#my-sa", item.get("SK").s());
        assertEquals("test-cluster", item.get("clusterName").s());
        assertEquals("default", item.get("namespace").s());
        assertEquals("my-sa", item.get("serviceAccount").s());
        assertEquals("arn:aws:iam::123456789012:role/test-role", item.get("roleArn").s());
        assertTrue(item.get("associationId").s().startsWith("assoc-"));
    }

    @Test
    void createAssociation_throws_whenDuplicate() {
        mockRoleExists();
        mockGetItem(Map.of(
            "PK", AttributeValue.fromS("CLUSTER#test-cluster"),
            "SK", AttributeValue.fromS("default#my-sa"),
            "roleArn", AttributeValue.fromS("arn:aws:iam::123456789012:role/existing")
        ));

        var ex = assertThrows(IllegalStateException.class,
            () -> service.createAssociation("test-cluster", "default",
                "my-sa", "arn:aws:iam::123456789012:role/new-role"));
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    void createAssociation_throws_whenClusterNameBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> service.createAssociation("", "default", "my-sa", "arn:aws:iam::123456789012:role/r"));
    }

    @Test
    void createAssociation_throws_whenClusterNameNull() {
        assertThrows(IllegalArgumentException.class,
            () -> service.createAssociation(null, "default", "my-sa", "arn:aws:iam::123456789012:role/r"));
    }

    @Test
    void createAssociation_throws_whenNamespaceBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> service.createAssociation("cluster", "", "my-sa", "arn:aws:iam::123456789012:role/r"));
    }

    @Test
    void createAssociation_throws_whenServiceAccountBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> service.createAssociation("cluster", "default", "", "arn:aws:iam::123456789012:role/r"));
    }

    @Test
    void createAssociation_throws_whenRoleArnBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> service.createAssociation("cluster", "default", "my-sa", ""));
    }

    @Test
    void createAssociation_throws_whenRoleArnNull() {
        assertThrows(IllegalArgumentException.class,
            () -> service.createAssociation("cluster", "default", "my-sa", null));
    }

    // --- listAssociations ---

    @Test
    void listAssociations_returnsAll_forCluster() {
        when(dynamoDb.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(
                Map.of(
                    "associationId", AttributeValue.fromS("assoc-1"),
                    "clusterName", AttributeValue.fromS("test-cluster"),
                    "namespace", AttributeValue.fromS("default"),
                    "serviceAccount", AttributeValue.fromS("sa-1"),
                    "roleArn", AttributeValue.fromS("arn:aws:iam::123456789012:role/role-1")),
                Map.of(
                    "associationId", AttributeValue.fromS("assoc-2"),
                    "clusterName", AttributeValue.fromS("test-cluster"),
                    "namespace", AttributeValue.fromS("kube-system"),
                    "serviceAccount", AttributeValue.fromS("sa-2"),
                    "roleArn", AttributeValue.fromS("arn:aws:iam::123456789012:role/role-2"))
            ).build());

        List<Map<String, String>> result = service.listAssociations("test-cluster", null, null);

        assertEquals(2, result.size());
        assertEquals("assoc-1", result.get(0).get("associationId"));
        assertEquals("assoc-2", result.get(1).get("associationId"));
    }

    @Test
    void listAssociations_filtersBy_namespace() {
        when(dynamoDb.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(List.of()).build());

        service.listAssociations("test-cluster", "kube-system", null);

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).query(captor.capture());
        assertTrue(captor.getValue().keyConditionExpression().contains("begins_with(SK, :skPrefix)"));
        assertEquals("kube-system#",
            captor.getValue().expressionAttributeValues().get(":skPrefix").s());
    }

    @Test
    void listAssociations_filtersBy_namespaceAndServiceAccount() {
        when(dynamoDb.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(List.of()).build());

        service.listAssociations("test-cluster", "default", "my-sa");

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).query(captor.capture());
        assertEquals("serviceAccount = :sa", captor.getValue().filterExpression());
        assertEquals("my-sa",
            captor.getValue().expressionAttributeValues().get(":sa").s());
    }

    @Test
    void listAssociations_returnsEmpty_whenNone() {
        when(dynamoDb.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(List.of()).build());

        assertTrue(service.listAssociations("test-cluster", null, null).isEmpty());
    }

    // --- describeAssociation ---

    @Test
    void describeAssociation_returnsDetails_whenFound() {
        when(dynamoDb.scan(any(ScanRequest.class)))
            .thenReturn(ScanResponse.builder().items(List.of(Map.of(
                "associationId", AttributeValue.fromS("assoc-abc"),
                "clusterName", AttributeValue.fromS("test-cluster"),
                "namespace", AttributeValue.fromS("default"),
                "serviceAccount", AttributeValue.fromS("my-sa"),
                "roleArn", AttributeValue.fromS("arn:aws:iam::123456789012:role/test-role"),
                "createdAt", AttributeValue.fromS("2025-01-01T00:00:00Z")
            ))).build());

        Map<String, String> result = service.describeAssociation("test-cluster", "assoc-abc");

        assertNotNull(result);
        assertEquals("assoc-abc", result.get("associationId"));
        assertEquals("default", result.get("namespace"));
        assertEquals("my-sa", result.get("serviceAccount"));
        assertEquals("arn:aws:iam::123456789012:role/test-role", result.get("roleArn"));
    }

    @Test
    void describeAssociation_returnsNull_whenNotFound() {
        when(dynamoDb.scan(any(ScanRequest.class)))
            .thenReturn(ScanResponse.builder().items(List.of()).build());

        assertNull(service.describeAssociation("test-cluster", "assoc-missing"));
    }

    @Test
    void describeAssociation_usesCorrectFilter() {
        when(dynamoDb.scan(any(ScanRequest.class)))
            .thenReturn(ScanResponse.builder().items(List.of()).build());

        service.describeAssociation("my-cluster", "assoc-xyz");

        ArgumentCaptor<ScanRequest> captor = ArgumentCaptor.forClass(ScanRequest.class);
        verify(dynamoDb).scan(captor.capture());
        assertEquals("CLUSTER#my-cluster",
            captor.getValue().expressionAttributeValues().get(":pk").s());
        assertEquals("assoc-xyz",
            captor.getValue().expressionAttributeValues().get(":aid").s());
    }

    // --- deleteAssociation ---

    @Test
    void deleteAssociation_succeeds_whenFound() {
        when(dynamoDb.scan(any(ScanRequest.class)))
            .thenReturn(ScanResponse.builder().items(List.of(Map.of(
                "associationId", AttributeValue.fromS("assoc-abc"),
                "clusterName", AttributeValue.fromS("test-cluster"),
                "namespace", AttributeValue.fromS("default"),
                "serviceAccount", AttributeValue.fromS("my-sa"),
                "roleArn", AttributeValue.fromS("arn:aws:iam::123456789012:role/test-role")
            ))).build());
        when(dynamoDb.deleteItem(any(DeleteItemRequest.class)))
            .thenReturn(DeleteItemResponse.builder().build());

        service.deleteAssociation("test-cluster", "assoc-abc");

        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb).deleteItem(captor.capture());
        assertEquals("CLUSTER#test-cluster", captor.getValue().key().get("PK").s());
        assertEquals("default#my-sa", captor.getValue().key().get("SK").s());
    }

    @Test
    void deleteAssociation_throws_whenNotFound() {
        when(dynamoDb.scan(any(ScanRequest.class)))
            .thenReturn(ScanResponse.builder().items(List.of()).build());

        var ex = assertThrows(IllegalArgumentException.class,
            () -> service.deleteAssociation("test-cluster", "assoc-missing"));
        assertTrue(ex.getMessage().contains("Association not found"));
    }

    // --- role validation (EPIA parity with real EKS) ---

    @Test
    void createAssociation_throws_whenRoleDoesNotExist() {
        when(iamClient.getRole(any(GetRoleRequest.class)))
            .thenThrow(NoSuchEntityException.builder()
                .message("Role not found").build());

        var ex = assertThrows(IllegalArgumentException.class,
            () -> service.createAssociation("cluster", "default", "my-sa",
                "arn:aws:iam::123456789012:role/nonexistent-role"));
        assertTrue(ex.getMessage().contains("Role provided in the request does not exist"));
    }

    @Test
    void createAssociation_validatesRoleBeforeDuplicateCheck() {
        // Role validation should happen before the DynamoDB duplicate check
        when(iamClient.getRole(any(GetRoleRequest.class)))
            .thenThrow(NoSuchEntityException.builder()
                .message("Role not found").build());

        assertThrows(IllegalArgumentException.class,
            () -> service.createAssociation("cluster", "default", "my-sa",
                "arn:aws:iam::123456789012:role/missing"));
        // DynamoDB should never be called if role doesn't exist
        verify(dynamoDb, never()).getItem(any(GetItemRequest.class));
    }

    @Test
    void validateRoleExists_extractsRoleNameFromArn() {
        mockRoleExists();

        service.validateRoleExists("arn:aws:iam::123456789012:role/my-role");

        ArgumentCaptor<GetRoleRequest> captor = ArgumentCaptor.forClass(GetRoleRequest.class);
        verify(iamClient).getRole(captor.capture());
        assertEquals("my-role", captor.getValue().roleName());
    }

    @Test
    void validateRoleExists_handlesPathInArn() {
        mockRoleExists();

        service.validateRoleExists("arn:aws:iam::123456789012:role/service-role/my-role");

        ArgumentCaptor<GetRoleRequest> captor = ArgumentCaptor.forClass(GetRoleRequest.class);
        verify(iamClient).getRole(captor.capture());
        assertEquals("my-role", captor.getValue().roleName());
    }

    @Test
    void validateRoleExists_throws_whenTrustPolicyEmpty() {
        when(iamClient.getRole(any(GetRoleRequest.class)))
            .thenReturn(GetRoleResponse.builder()
                .role(Role.builder().roleName("r").arn("arn:aws:iam::123:role/r")
                    .assumeRolePolicyDocument("").build())
                .build());

        var ex = assertThrows(IllegalArgumentException.class,
            () -> service.validateRoleExists("arn:aws:iam::123:role/r"));
        assertTrue(ex.getMessage().contains("trust policy is empty"));
    }

    @Test
    void validateRoleExists_throws_whenTrustPolicyMissingAssumeRole() {
        // Trust policy that only allows lambda:InvokeFunction, not sts:AssumeRole
        String policy = java.net.URLEncoder.encode(
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"lambda:InvokeFunction\"}]}",
            java.nio.charset.StandardCharsets.UTF_8);
        when(iamClient.getRole(any(GetRoleRequest.class)))
            .thenReturn(GetRoleResponse.builder()
                .role(Role.builder().roleName("r").arn("arn:aws:iam::123:role/r")
                    .assumeRolePolicyDocument(policy).build())
                .build());

        var ex = assertThrows(IllegalArgumentException.class,
            () -> service.validateRoleExists("arn:aws:iam::123:role/r"));
        assertTrue(ex.getMessage().contains("does not allow sts:AssumeRole"));
    }

    @Test
    void validateRoleExists_accepts_roleWithAssumeRoleTrust() {
        String policy = java.net.URLEncoder.encode(
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"pods.eks.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}",
            java.nio.charset.StandardCharsets.UTF_8);
        when(iamClient.getRole(any(GetRoleRequest.class)))
            .thenReturn(GetRoleResponse.builder()
                .role(Role.builder().roleName("r").arn("arn:aws:iam::123:role/r")
                    .assumeRolePolicyDocument(policy).build())
                .build());

        assertDoesNotThrow(() -> service.validateRoleExists("arn:aws:iam::123:role/r"));
    }

    @Test
    void validateRoleExists_accepts_roleWithStarAction() {
        String policy = java.net.URLEncoder.encode(
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::123:root\"},\"Action\":\"sts:*\"}]}",
            java.nio.charset.StandardCharsets.UTF_8);
        when(iamClient.getRole(any(GetRoleRequest.class)))
            .thenReturn(GetRoleResponse.builder()
                .role(Role.builder().roleName("r").arn("arn:aws:iam::123:role/r")
                    .assumeRolePolicyDocument(policy).build())
                .build());

        assertDoesNotThrow(() -> service.validateRoleExists("arn:aws:iam::123:role/r"));
    }

    // --- helpers ---

    private void mockGetItem(Map<String, AttributeValue> item) {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(item).build());
    }

    private void mockGetItemEmpty() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().build());
    }

    private void mockRoleExists() {
        String trustPolicy = java.net.URLEncoder.encode(
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"pods.eks.amazonaws.com\"},\"Action\":[\"sts:AssumeRole\",\"sts:TagSession\"]}]}",
            java.nio.charset.StandardCharsets.UTF_8);
        lenient().when(iamClient.getRole(any(GetRoleRequest.class)))
            .thenReturn(GetRoleResponse.builder()
                .role(Role.builder().roleName("test-role").arn("arn:aws:iam::123456789012:role/test-role")
                    .assumeRolePolicyDocument(trustPolicy).build())
                .build());
    }
}
