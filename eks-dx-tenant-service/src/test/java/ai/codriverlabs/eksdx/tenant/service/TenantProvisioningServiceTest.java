package ai.codriverlabs.eksdx.tenant.service;

import ai.codriverlabs.eksdx.tenant.model.TenantItem;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantProvisioningServiceTest {

    @Mock DynamoDbClient dynamoDb;

    TenantProvisioningService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new TenantProvisioningService();
        setField("dynamoDb", dynamoDb);
        setField("tenantsTable", "eks-dx-tenants");
        setField("tenantIdGenerator", new TenantIdGenerator());
        // maxTenantsPerCaller
        Field quota = TenantProvisioningService.class.getDeclaredField("maxTenantsPerCaller");
        quota.setAccessible(true);
        quota.setInt(service, 5);
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

        // Only DynamoDB putItem — no EC2, IAM, Secrets Manager calls
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
    // Helpers
    // -------------------------------------------------------------------------

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
