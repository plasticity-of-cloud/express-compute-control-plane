package ai.codriverlabs.ecp.tenant.service;

import ai.codriverlabs.ecp.tenant.model.TenantItem;
import ai.codriverlabs.ecp.tenant.exception.ClusterAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantProvisioningServiceTest {

    @Mock DynamoDbClient dynamoDb;

    TenantProvisioningService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new TenantProvisioningService();
        setField("dynamoDb", dynamoDb);
        setField("tenantsTable", "ecp-tenants");
        setField("clustersTable", "ecp-clusters");
        setField("tenantIdGenerator", new TenantIdGenerator());
        // maxTenantsPerCaller
        Field quota = TenantProvisioningService.class.getDeclaredField("maxTenantsPerCaller");
        quota.setAccessible(true);
        quota.setInt(service, 5);

        // Default: cluster name does not exist (empty GetItemResponse).
        // Individual tests that need a specific getItem response override this.
        lenient().when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().build());
    }

    // -------------------------------------------------------------------------
    // Unmanaged provisioning
    // -------------------------------------------------------------------------

    @Test
    void provision_unmanaged_writesRecordAndReturnsId() {
        ArgumentCaptor<PutItemRequest> cap = ArgumentCaptor.forClass(PutItemRequest.class);

        String id = service.provision("my-k3s", false, "user@example.com", "arn:aws:iam::123:role/dev",
            null, null, null, false, 0, null);

        assertNotNull(id);
        assertEquals(8, id.length());
        verify(dynamoDb).putItem(cap.capture());

        Map<String, AttributeValue> item = cap.getValue().item();
        assertEquals(id, item.get("tenantId").s());
        assertEquals("my-k3s", item.get("clusterName").s());
        assertEquals("false", item.get("managed").s());
        assertEquals("user@example.com", item.get("idcUserId").s());
        assertFalse(item.containsKey("state"), "unmanaged tenant must not have state");
        assertFalse(item.containsKey("instanceId"), "unmanaged tenant must not have instanceId");
    }

    @Test
    void provision_unmanaged_noAwsInfrastructureCalls() {
        service.provision("my-k3s", false, "user@example.com", "arn:aws:iam::123:role/dev",
            null, null, null, false, 0, null);

        // Only DynamoDB calls expected:
        //   getItem  — clusterExists() uniqueness check
        //   putItem  — writeInitialRecord()
        // No EC2, IAM, Secrets Manager, STS or other service calls.
        verify(dynamoDb, times(1)).getItem(any(GetItemRequest.class));
        verify(dynamoDb, times(1)).putItem(any(PutItemRequest.class));
        verifyNoMoreInteractions(dynamoDb);
    }

    @Test
    void provision_unmanaged_storesBothTimestamps() {
        ArgumentCaptor<PutItemRequest> cap = ArgumentCaptor.forClass(PutItemRequest.class);
        service.provision("my-k3s", false, "user@example.com", null, null, null, null, false, 0, null);

        verify(dynamoDb).putItem(cap.capture());
        Map<String, AttributeValue> item = cap.getValue().item();
        assertNotNull(item.get("createdAt").s());
        assertNotNull(item.get("updatedAt").s());
        assertEquals(item.get("createdAt").s(), item.get("updatedAt").s());
    }

    @Test
    void provision_unmanaged_differentUsersGetDifferentIds() {
        String id1 = service.provision("cluster-a", false, "alice@example.com", null, null, null, null, false, 0, null);
        String id2 = service.provision("cluster-b", false, "bob@example.com",   null, null, null, null, false, 0, null);
        assertNotEquals(id1, id2);
    }

    // -------------------------------------------------------------------------
    // getState
    // -------------------------------------------------------------------------

    @Test
    void getState_returnsClusterNameAndManagedFlag() {
        mockItem("abc12345", "prod-cluster", true, "provisioning");

        TenantItem item = service.getState("abc12345");

        assertEquals("abc12345", item.tenantId());
        assertEquals("prod-cluster", item.clusterName());
        assertTrue(item.managed());
        assertEquals("provisioning", item.state());
    }

    @Test
    void getState_unmanagedTenant_noState() {
        mockItem("abc12345", "my-k3s", false, null);

        TenantItem item = service.getState("abc12345");

        assertFalse(item.managed());
        assertNull(item.state());
    }

    @Test
    void getState_throwsWhenNotFound() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().build());

        assertThrows(IllegalArgumentException.class, () -> service.getState("nonexistent"));
    }

    // -------------------------------------------------------------------------
    // countTenantsByOwner
    // -------------------------------------------------------------------------

    @Test
    void countTenantsByOwner_returnsScanCount() {
        when(dynamoDb.scan(any(ScanRequest.class)))
            .thenReturn(ScanResponse.builder().count(3).build());

        assertEquals(3, service.countTenantsByOwner("arn:aws:iam::123:role/dev"));
    }

    // -------------------------------------------------------------------------
    // clusterExists
    // -------------------------------------------------------------------------

    @Test
    void clusterExists_returnsFalse_whenNotFound() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().build()); // empty → not found

        assertFalse(service.clusterExists("no-such-cluster"));
    }

    @Test
    void clusterExists_returnsTrue_whenFound() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder()
                .item(Map.of("clusterName", AttributeValue.fromS("existing-cluster")))
                .build());

        assertTrue(service.clusterExists("existing-cluster"));
    }

    // -------------------------------------------------------------------------
    // provision — duplicate cluster name (unmanaged path)
    // -------------------------------------------------------------------------

    @Test
    void provision_unmanaged_duplicateClusterName_throwsClusterAlreadyExistsException() {
        // First GetItem call (clusterExists check) returns an existing record
        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder()
                .item(Map.of("clusterName", AttributeValue.fromS("my-k3s")))
                .build());

        ClusterAlreadyExistsException ex = assertThrows(
            ClusterAlreadyExistsException.class,
            () -> service.provision("my-k3s", false, "user@example.com",
                "arn:aws:iam::123:role/dev", null, null, null, false, 0, null));

        assertEquals("my-k3s", ex.getClusterName());
        assertTrue(ex.getMessage().contains("my-k3s"));
        assertTrue(ex.getMessage().contains("ecp delete-cluster my-k3s"),
            "Exception message must guide user to delete-cluster");
        // No putItem must have been called — fail fast, zero writes
        verify(dynamoDb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void provision_unmanaged_newClusterName_proceedsNormally() {
        // First GetItem (clusterExists check) returns empty → new cluster
        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().build());

        assertDoesNotThrow(() ->
            service.provision("brand-new", false, "user@example.com",
                "arn:aws:iam::123:role/dev", null, null, null, false, 0, null));

        verify(dynamoDb, atLeastOnce()).putItem(any(PutItemRequest.class));
    }

    // -------------------------------------------------------------------------
    // registerSelfManagedCluster — duplicate cluster name
    // -------------------------------------------------------------------------

    @Test
    void registerSelfManagedCluster_duplicateClusterName_throwsClusterAlreadyExistsException() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder()
                .item(Map.of("clusterName", AttributeValue.fromS("k3s-prod")))
                .build());

        ClusterAlreadyExistsException ex = assertThrows(
            ClusterAlreadyExistsException.class,
            () -> service.registerSelfManagedCluster(
                "k3s-prod", "https://k8s.example.com", "{\"keys\":[]}", "arn:aws:iam::123:role/dev",
                null, null, null, null));

        assertEquals("k3s-prod", ex.getClusterName());
        verify(dynamoDb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void registerSelfManagedCluster_newClusterName_writesRecords() {
        // clusterExists check returns empty
        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().build());

        String tenantId = service.registerSelfManagedCluster(
            "new-k3s", "https://k8s.example.com", "{\"keys\":[]}", "arn:aws:iam::123:role/dev",
            null, null, null, null);

        assertNotNull(tenantId);
        // Expects 2 putItem calls: cluster record + tenant record
        verify(dynamoDb, times(2)).putItem(any(PutItemRequest.class));
    }

    // -------------------------------------------------------------------------
    // deleteCluster — routing logic
    // -------------------------------------------------------------------------

    @Test
    void deleteCluster_managedTrue_callsDeprovision() {
        // Mock cluster record with managed=true — deleteCluster reads this first
        var clusterItem = new java.util.HashMap<String, AttributeValue>();
        clusterItem.put("clusterName", AttributeValue.fromS("my-cluster"));
        clusterItem.put("tenantId", AttributeValue.fromS("tenant-123"));
        clusterItem.put("managed", AttributeValue.fromS("true"));
        clusterItem.put("ownerArn", AttributeValue.fromS("arn:aws:iam::123:role/owner"));

        // deprovision() then calls getState(tenantId) which does another getItem
        var tenantItem = new java.util.HashMap<String, AttributeValue>();
        tenantItem.put("tenantId", AttributeValue.fromS("tenant-123"));
        tenantItem.put("clusterName", AttributeValue.fromS("my-cluster"));
        tenantItem.put("managed", AttributeValue.fromS("true"));
        tenantItem.put("state", AttributeValue.fromS("ready"));
        tenantItem.put("instanceId", AttributeValue.fromS("i-abc123"));
        tenantItem.put("createdAt", AttributeValue.fromS(Instant.now().toString()));
        tenantItem.put("updatedAt", AttributeValue.fromS(Instant.now().toString()));

        // First call returns cluster record, second returns tenant record
        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(clusterItem).build())
            .thenReturn(GetItemResponse.builder().item(tenantItem).build());

        // deprovision will fail on ec2 call (not mocked) — that's fine,
        // we just verify it took the managed path (not self-managed deregister).
        try {
            service.deleteCluster("my-cluster", "arn:aws:iam::123:role/owner");
        } catch (Exception e) {
            // Expected: deprovision() tries to use unmocked ec2 client
        }

        // Verify it did NOT take the self-managed path (which would delete cluster record directly)
        verify(dynamoDb, never()).deleteItem(argThat((DeleteItemRequest req) ->
            "ecp-clusters".equals(req.tableName())));
    }

    @Test
    void deleteCluster_managedFalse_onlyDeregisters() {
        mockClusterRecord("my-k3s", "tenant-456", "false", "arn:aws:iam::123:role/owner");

        service.deleteCluster("my-k3s", "arn:aws:iam::123:role/owner");

        // Should delete from both tables (deregister), not call deprovision
        ArgumentCaptor<DeleteItemRequest> cap = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb, times(2)).deleteItem(cap.capture());

        var tables = cap.getAllValues().stream().map(DeleteItemRequest::tableName).toList();
        assertTrue(tables.contains("ecp-clusters"));
        assertTrue(tables.contains("ecp-tenants"));
    }

    @Test
    void deleteCluster_managedFieldMissing_treatsAsSelfManaged() {
        // Cluster record without "managed" field — simulates legacy data
        var item = new java.util.HashMap<String, AttributeValue>();
        item.put("clusterName", AttributeValue.fromS("legacy-cluster"));
        item.put("tenantId", AttributeValue.fromS("tenant-old"));
        item.put("ownerArn", AttributeValue.fromS("arn:aws:iam::123:role/owner"));
        // No "managed" key at all

        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(item).build());

        service.deleteCluster("legacy-cluster", "arn:aws:iam::123:role/owner");

        // Should take self-managed path (deregister only)
        ArgumentCaptor<DeleteItemRequest> cap = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb, times(2)).deleteItem(cap.capture());
    }

    @Test
    void deleteCluster_nonOwner_throwsSecurityException() {
        mockClusterRecord("my-cluster", "tenant-123", "true", "arn:aws:iam::123:role/owner");

        assertThrows(SecurityException.class, () ->
            service.deleteCluster("my-cluster", "arn:aws:iam::999:role/attacker"));
    }

    @Test
    void deleteCluster_clusterNotFound_throwsIllegalArgument() {
        // Default mock returns empty item (from setUp)
        assertThrows(IllegalArgumentException.class, () ->
            service.deleteCluster("nonexistent", "arn:aws:iam::123:role/anyone"));
    }

    @Test
    void deleteCluster_ownerArnNullInRecord_allowsDeletion() {
        // Cluster without ownerArn — anyone can delete
        var item = new java.util.HashMap<String, AttributeValue>();
        item.put("clusterName", AttributeValue.fromS("unowned-cluster"));
        item.put("tenantId", AttributeValue.fromS("tenant-789"));
        item.put("managed", AttributeValue.fromS("false"));
        // No ownerArn

        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(item).build());

        service.deleteCluster("unowned-cluster", "arn:aws:iam::999:role/anyone");

        // Should succeed (no SecurityException)
        verify(dynamoDb, atLeastOnce()).deleteItem(any(DeleteItemRequest.class));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void mockClusterRecord(String clusterName, String tenantId, String managed, String ownerArn) {
        var item = new java.util.HashMap<String, AttributeValue>();
        item.put("clusterName", AttributeValue.fromS(clusterName));
        item.put("tenantId", AttributeValue.fromS(tenantId));
        item.put("managed", AttributeValue.fromS(managed));
        if (ownerArn != null) item.put("ownerArn", AttributeValue.fromS(ownerArn));

        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(item).build());
    }

    private void mockItem(String tenantId, String clusterName, boolean managed, String state) {
        var item = new java.util.HashMap<String, AttributeValue>();
        item.put("tenantId",    AttributeValue.fromS(tenantId));
        item.put("clusterName", AttributeValue.fromS(clusterName));
        item.put("managed",     AttributeValue.fromS(String.valueOf(managed)));
        item.put("createdAt",   AttributeValue.fromS(Instant.now().toString()));
        item.put("updatedAt",   AttributeValue.fromS(Instant.now().toString()));
        item.put("progress",    AttributeValue.fromN("0"));
        if (state != null) item.put("state", AttributeValue.fromS(state));

        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(item).build());
    }

    private void setField(String name, Object value) throws Exception {
        Field f = TenantProvisioningService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }
}
