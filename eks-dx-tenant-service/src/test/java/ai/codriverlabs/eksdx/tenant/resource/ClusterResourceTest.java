package ai.codriverlabs.eksdx.tenant.resource;

import ai.codriverlabs.eksdx.tenant.exception.ClusterAlreadyExistsException;
import ai.codriverlabs.eksdx.tenant.service.TenantProvisioningService;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ClusterResource}.
 *
 * Covers the duplicate-cluster-name 409 fix for both managed and self-managed modes,
 * plus the happy paths that must remain unaffected.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClusterResourceTest {

    @Mock TenantProvisioningService provisioningService;
    @Mock ContainerRequestContext ctx;

    ClusterResource resource;

    @BeforeEach
    void setUp() throws Exception {
        resource = new ClusterResource();
        setField("provisioningService", provisioningService);
        setField("deploymentMode", "hybrid"); // default: both flows allowed

        when(ctx.getProperty("callerArn")).thenReturn("arn:aws:iam::123:role/dev");
        when(ctx.getProperty("idcUserId")).thenReturn("user@example.com");
        when(ctx.getProperty("sourceIp")).thenReturn("10.0.0.1");
    }

    // -------------------------------------------------------------------------
    // Managed mode — duplicate name
    // -------------------------------------------------------------------------

    @Test
    void managedMode_duplicateClusterName_returns409() {
        when(provisioningService.countTenantsByOwner(any())).thenReturn(0);
        when(provisioningService.getMaxTenantsPerCaller()).thenReturn(5);
        when(provisioningService.provision(any(), anyBoolean(), any(), any(), any(), any(), any(),
            anyBoolean(), anyInt(), any()))
            .thenThrow(new ClusterAlreadyExistsException("my-cluster"));

        Response r = resource.createCluster(managedReq("my-cluster"), ctx);

        assertEquals(409, r.getStatus());
        assertErrorCode(r, "ResourceInUseException");
        assertMessageContains(r, "my-cluster");
    }

    @Test
    void managedMode_duplicateClusterName_messageIsActionable() {
        when(provisioningService.countTenantsByOwner(any())).thenReturn(0);
        when(provisioningService.getMaxTenantsPerCaller()).thenReturn(5);
        when(provisioningService.provision(any(), anyBoolean(), any(), any(), any(), any(), any(),
            anyBoolean(), anyInt(), any()))
            .thenThrow(new ClusterAlreadyExistsException("prod-cluster"));

        Response r = resource.createCluster(managedReq("prod-cluster"), ctx);

        assertEquals(409, r.getStatus());
        // Message must instruct user how to resolve the conflict
        assertMessageContains(r, "eks-dx delete-cluster prod-cluster");
    }

    // -------------------------------------------------------------------------
    // Self-managed mode — duplicate name
    // -------------------------------------------------------------------------

    @Test
    void selfManagedMode_duplicateClusterName_returns409() {
        when(provisioningService.registerSelfManagedCluster(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new ClusterAlreadyExistsException("k3s-cluster"));

        Response r = resource.createCluster(selfManagedReq("k3s-cluster"), ctx);

        assertEquals(409, r.getStatus());
        assertErrorCode(r, "ResourceInUseException");
        assertMessageContains(r, "k3s-cluster");
    }

    @Test
    void selfManagedMode_duplicateClusterName_messageIsActionable() {
        when(provisioningService.registerSelfManagedCluster(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new ClusterAlreadyExistsException("k3s-cluster"));

        Response r = resource.createCluster(selfManagedReq("k3s-cluster"), ctx);

        assertMessageContains(r, "eks-dx delete-cluster k3s-cluster");
    }

    // -------------------------------------------------------------------------
    // Happy path — must not regress
    // -------------------------------------------------------------------------

    @Test
    void managedMode_newClusterName_returns202() {
        when(provisioningService.countTenantsByOwner(any())).thenReturn(0);
        when(provisioningService.getMaxTenantsPerCaller()).thenReturn(5);
        when(provisioningService.provision(any(), anyBoolean(), any(), any(), any(), any(), any(),
            anyBoolean(), anyInt(), any())).thenReturn("abc123");

        Response r = resource.createCluster(managedReq("new-cluster"), ctx);

        assertEquals(202, r.getStatus());
        assertResponseField(r, "tenantId", "abc123");
        assertResponseField(r, "clusterName", "new-cluster");
    }

    @Test
    void selfManagedMode_newClusterName_returns201() {
        when(provisioningService.registerSelfManagedCluster(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn("def456");

        Response r = resource.createCluster(selfManagedReq("new-k3s"), ctx);

        assertEquals(201, r.getStatus());
        assertResponseField(r, "tenantId", "def456");
        assertResponseField(r, "clusterName", "new-k3s");
    }

    // -------------------------------------------------------------------------
    // Other error codes must not be affected
    // -------------------------------------------------------------------------

    @Test
    void managedMode_quotaExceeded_returns429() {
        when(provisioningService.countTenantsByOwner(any())).thenReturn(5);
        when(provisioningService.getMaxTenantsPerCaller()).thenReturn(5);

        Response r = resource.createCluster(managedReq("any-cluster"), ctx);

        assertEquals(429, r.getStatus());
        assertErrorCode(r, "QuotaExceededException");
    }

    @Test
    void missingCallerArn_returns403() {
        when(ctx.getProperty("callerArn")).thenReturn(null);
        Response r = resource.createCluster(managedReq("any-cluster"), ctx);
        assertEquals(403, r.getStatus());
    }

    @Test
    void invalidClusterName_returns400() {
        Response r = resource.createCluster(managedReq("1-invalid"), ctx);
        assertEquals(400, r.getStatus());
        assertErrorCode(r, "InvalidParameterException");
    }

    @Test
    void missingClusterName_returns400() {
        Response r = resource.createCluster(managedReq(null), ctx);
        assertEquals(400, r.getStatus());
    }

    // -------------------------------------------------------------------------
    // Deployment mode guard — managed mode rejects self-managed
    // -------------------------------------------------------------------------

    @Test
    void managedMode_rejectsSelfManagedRegistration() throws Exception {
        setField("deploymentMode", "managed");

        Response r = resource.createCluster(selfManagedReq("k3s-cluster"), ctx);

        assertEquals(400, r.getStatus());
        assertErrorCode(r, "InvalidParameterException");
        assertMessageContains(r, "disabled in managed-only deployment mode");
    }

    @Test
    void hybridMode_allowsSelfManagedRegistration() throws Exception {
        setField("deploymentMode", "hybrid");
        when(provisioningService.registerSelfManagedCluster(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn("abc123");

        Response r = resource.createCluster(selfManagedReq("k3s-cluster"), ctx);

        assertEquals(201, r.getStatus());
    }

    @Test
    void managedMode_allowsManagedProvisioning() throws Exception {
        setField("deploymentMode", "managed");
        when(provisioningService.countTenantsByOwner(any())).thenReturn(0);
        when(provisioningService.getMaxTenantsPerCaller()).thenReturn(5);
        when(provisioningService.provision(any(), anyBoolean(), any(), any(), any(), any(), any(),
            anyBoolean(), anyInt(), any())).thenReturn("tenant123");

        Response r = resource.createCluster(managedReq("managed-cluster"), ctx);

        assertEquals(202, r.getStatus());
    }

    // -------------------------------------------------------------------------
    // ClusterAlreadyExistsException unit
    // -------------------------------------------------------------------------

    @Test
    void clusterAlreadyExistsException_messageContainsDeleteCommand() {
        var ex = new ClusterAlreadyExistsException("foo-bar");
        assertTrue(ex.getMessage().contains("foo-bar"));
        assertTrue(ex.getMessage().contains("eks-dx delete-cluster foo-bar"),
            "Message should guide user to delete-cluster command");
        assertEquals("foo-bar", ex.getClusterName());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ClusterResource.CreateClusterRequest managedReq(String clusterName) {
        var r = new ClusterResource.CreateClusterRequest();
        r.clusterName = clusterName;
        // no jwks/issuer → managed mode
        return r;
    }

    private static ClusterResource.CreateClusterRequest selfManagedReq(String clusterName) {
        var r = new ClusterResource.CreateClusterRequest();
        r.clusterName = clusterName;
        r.jwks = "{\"keys\":[]}";
        r.issuer = "https://kubernetes.default.svc";
        return r;
    }

    @SuppressWarnings("unchecked")
    private void assertErrorCode(Response r, String expectedCode) {
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals(expectedCode, body.get("__type"),
            "Expected error code " + expectedCode + " but got: " + body.get("__type"));
    }

    @SuppressWarnings("unchecked")
    private void assertMessageContains(Response r, String expected) {
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        String msg = (String) body.get("message");
        assertNotNull(msg, "Response message must not be null");
        assertTrue(msg.contains(expected),
            "Expected message to contain '" + expected + "' but was: " + msg);
    }

    @SuppressWarnings("unchecked")
    private void assertResponseField(Response r, String key, Object expected) {
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals(expected, body.get(key),
            "Expected field '" + key + "' = " + expected + " but was: " + body.get(key));
    }

    private void setField(String name, Object value) throws Exception {
        Field f = ClusterResource.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(resource, value);
    }
}
