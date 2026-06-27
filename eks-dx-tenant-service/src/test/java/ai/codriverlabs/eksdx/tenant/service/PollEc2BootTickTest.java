package ai.codriverlabs.eksdx.tenant.service;

import ai.codriverlabs.eksdx.tenant.model.TenantItem;
import ai.codriverlabs.eksdx.tenant.model.TenantProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PollEc2BootTickTest {

    @Mock Ec2Client ec2;
    @Mock DynamoDbClient dynamoDb;

    TenantProvisioningService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new TenantProvisioningService();
        setField("ec2", ec2);
        setField("dynamoDb", dynamoDb);
        setField("tenantsTable", "eks-d-xpress-tenants");
    }

    @Test
    void returnsNull_whenNoInstanceId() {
        mockTenantItem("test", null, "provisioning");
        assertNull(service.pollEc2BootTick("test"));
    }

    @Test
    void returnsRunningWithIp_whenInstanceReady() {
        mockTenantItem("test", "i-123", "provisioning");
        mockDescribeInstances("running", "1.2.3.4");

        TenantProgress result = service.pollEc2BootTick("test");

        assertNotNull(result);
        assertEquals("provisioning", result.state());
        assertEquals("provisioning_started", result.phase());
        assertEquals("1.2.3.4", result.publicIp());
        assertEquals(25, result.progress());
    }

    @Test
    void returnsPending_whenInstanceNotRunning() {
        mockTenantItem("test", "i-123", "provisioning");
        mockDescribeInstances("pending", null);

        TenantProgress result = service.pollEc2BootTick("test");

        assertNotNull(result);
        assertEquals("provisioning", result.state());
        assertTrue(result.phase().contains("pending"));
        assertEquals(10, result.progress());
    }

    @Test
    void returnsFailed_whenInstanceTerminated() {
        mockTenantItem("test", "i-123", "provisioning");
        mockDescribeInstances("terminated", null);

        TenantProgress result = service.pollEc2BootTick("test");

        assertNotNull(result);
        assertEquals("failed", result.state());
    }

    @Test
    void returnsRunningNoIp_whenRunningButNoPublicIp() {
        mockTenantItem("test", "i-123", "provisioning");
        mockDescribeInstances("running", null);

        TenantProgress result = service.pollEc2BootTick("test");

        assertNotNull(result);
        assertEquals("provisioning", result.state());
        assertTrue(result.phase().contains("running"));
        assertEquals(10, result.progress());
    }

    private void mockTenantItem(String tenantId, String instanceId, String state) {
        var item = new java.util.HashMap<String, AttributeValue>();
        item.put("tenantId",    AttributeValue.fromS(tenantId));
        item.put("clusterName", AttributeValue.fromS("test-cluster"));
        item.put("managed",     AttributeValue.fromS("true"));
        item.put("createdAt",   AttributeValue.fromS(Instant.now().toString()));
        item.put("updatedAt",   AttributeValue.fromS(Instant.now().toString()));
        item.put("state",       AttributeValue.fromS(state));
        item.put("progress",    AttributeValue.fromN("0"));
        if (instanceId != null) item.put("instanceId", AttributeValue.fromS(instanceId));

        when(dynamoDb.getItem(any(software.amazon.awssdk.services.dynamodb.model.GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(item).build());
    }

    @SuppressWarnings("unchecked")
    private void mockDescribeInstances(String stateName, String publicIp) {
        var instanceBuilder = Instance.builder()
            .state(InstanceState.builder().name(stateName).build());
        if (publicIp != null) instanceBuilder.publicIpAddress(publicIp);

        var response = DescribeInstancesResponse.builder()
            .reservations(Reservation.builder()
                .instances(instanceBuilder.build())
                .build())
            .build();

        when(ec2.describeInstances(any(Consumer.class))).thenReturn(response);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = TenantProvisioningService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
