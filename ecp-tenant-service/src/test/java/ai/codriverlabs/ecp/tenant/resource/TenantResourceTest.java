package ai.codriverlabs.ecp.tenant.resource;

import ai.codriverlabs.ecp.tenant.service.DryRunProvisioningService;
import ai.codriverlabs.ecp.tenant.service.TenantProvisioningService;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantResourceTest {

    @Mock TenantProvisioningService provisioningService;
    @Mock DryRunProvisioningService dryRunService;
    @Mock ContainerRequestContext ctx;

    TenantResource resource;

    @BeforeEach
    void setUp() throws Exception {
        resource = new TenantResource();
        setField("provisioningService", provisioningService);
        setField("dryRunService", dryRunService);

        when(ctx.getProperty("callerArn")).thenReturn("arn:aws:iam::123:role/dev");
        when(ctx.getProperty("idcUserId")).thenReturn("user@example.com");
        when(ctx.getProperty("sourceIp")).thenReturn("1.2.3.4");
        when(dryRunService.isEnabled()).thenReturn(false);
    }

    private void stubHappyPath() {
        lenient().when(provisioningService.countTenantsByOwner(any())).thenReturn(0);
        lenient().when(provisioningService.getMaxTenantsPerCaller()).thenReturn(5);
        lenient().when(provisioningService.provision(any(), anyBoolean(), any(), any(), any(), any(), any(),
            anyBoolean(), anyInt(), any())).thenReturn("a1b2c3d4");
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Test
    void missingClusterName_returns400() {
        Response r = resource.createTenant(new TenantResource.CreateTenantRequest(), ctx);
        assertEquals(400, r.getStatus());
        assertErrorCode(r, "InvalidParameterException");
    }

    @Test
    void blankClusterName_returns400() {
        var req = req("  ", null);
        Response r = resource.createTenant(req, ctx);
        assertEquals(400, r.getStatus());
    }

    @Test
    void invalidClusterName_startsWithDigit_returns400() {
        Response r = resource.createTenant(req("1invalid", null), ctx);
        assertEquals(400, r.getStatus());
        assertErrorCode(r, "InvalidParameterException");
    }

    @Test
    void invalidClusterName_withUnderscore_returns400() {
        Response r = resource.createTenant(req("bad_name", null), ctx);
        assertEquals(400, r.getStatus());
    }

    @Test
    void validClusterName_accepted() {
        stubHappyPath();
        Response r = resource.createTenant(req("my-cluster", null), ctx);
        assertEquals(202, r.getStatus());
    }

    @Test
    void validClusterName_singleLetter_accepted() {
        stubHappyPath();
        Response r = resource.createTenant(req("a", null), ctx);
        assertEquals(202, r.getStatus());
    }

    // -------------------------------------------------------------------------
    // Auth
    // -------------------------------------------------------------------------

    @Test
    void missingCallerArn_returns403() {
        when(ctx.getProperty("callerArn")).thenReturn(null);
        Response r = resource.createTenant(req("my-cluster", null), ctx);
        assertEquals(403, r.getStatus());
        assertErrorCode(r, "AccessDeniedException");
    }

    // -------------------------------------------------------------------------
    // Quota (managed only)
    // -------------------------------------------------------------------------

    @Test
    void quotaExceeded_managed_returns429() {
        when(provisioningService.countTenantsByOwner(any())).thenReturn(5);
        when(provisioningService.getMaxTenantsPerCaller()).thenReturn(5);
        Response r = resource.createTenant(req("my-cluster", true), ctx);
        assertEquals(429, r.getStatus());
        assertErrorCode(r, "QuotaExceededException");
    }

    @Test
    void quotaExceeded_unmanaged_notEnforced() {
        lenient().when(provisioningService.provision(any(), eq(false), any(), any(), any(), any(), any(),
            anyBoolean(), anyInt(), any())).thenReturn("a1b2c3d4");
        Response r = resource.createTenant(req("my-k3s", false), ctx);
        assertEquals(202, r.getStatus());
        verify(provisioningService, never()).countTenantsByOwner(any());
    }

    // -------------------------------------------------------------------------
    // Managed vs unmanaged
    // -------------------------------------------------------------------------

    @Test
    void managedByDefault_whenManagedFieldAbsent() {
        stubHappyPath();
        Response r = resource.createTenant(req("my-cluster", null), ctx);
        assertEquals(202, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals(true, body.get("managed"));
    }

    @Test
    void unmanagedTenant_skipsEC2Provisioning() {
        when(provisioningService.provision(any(), eq(false), any(), any(), any(), any(), any(),
            anyBoolean(), anyInt(), any())).thenReturn("a1b2c3d4");
        Response r = resource.createTenant(req("my-k3s", false), ctx);
        assertEquals(202, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals(false, body.get("managed"));
        assertEquals("my-k3s", body.get("clusterName"));
        assertNotNull(body.get("tenantId"));
    }

    @Test
    void response_containsTenantIdAndClusterName() {
        stubHappyPath();
        Response r = resource.createTenant(req("my-cluster", true), ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("a1b2c3d4", body.get("tenantId"));
        assertEquals("my-cluster", body.get("clusterName"));
    }

    // -------------------------------------------------------------------------
    // SSH CIDR
    // -------------------------------------------------------------------------

    @Test
    void openSshCidr_returns400() {
        when(provisioningService.countTenantsByOwner(any())).thenReturn(0);
        when(provisioningService.getMaxTenantsPerCaller()).thenReturn(5);
        var req = req("my-cluster", true);
        req.sshCidr = "0.0.0.0/0";
        Response r = resource.createTenant(req, ctx);
        assertEquals(400, r.getStatus());
    }

    @Test
    void noSourceIp_andNoSshCidr_returns400() {
        when(provisioningService.countTenantsByOwner(any())).thenReturn(0);
        when(provisioningService.getMaxTenantsPerCaller()).thenReturn(5);
        when(ctx.getProperty("sourceIp")).thenReturn(null);
        Response r = resource.createTenant(req("my-cluster", true), ctx);
        assertEquals(400, r.getStatus());
    }

    @Test
    void explicitSshCidr_used() {
        stubHappyPath();
        var req = req("my-cluster", true);
        req.sshCidr = "10.0.0.1/32";
        Response r = resource.createTenant(req, ctx);
        assertEquals(202, r.getStatus());
        verify(provisioningService).provision(eq("my-cluster"), eq(true), any(), any(),
            any(), any(), any(), anyBoolean(), anyInt(), eq("10.0.0.1/32"));
    }

    // -------------------------------------------------------------------------
    // Arch / pricing validation (managed)
    // -------------------------------------------------------------------------

    @Test
    void invalidArch_returns400() {
        when(provisioningService.countTenantsByOwner(any())).thenReturn(0);
        when(provisioningService.getMaxTenantsPerCaller()).thenReturn(5);
        var req = req("my-cluster", true);
        req.arch = "risc-v";
        Response r = resource.createTenant(req, ctx);
        assertEquals(400, r.getStatus());
    }

    @Test
    void invalidPricingModel_returns400() {
        when(provisioningService.countTenantsByOwner(any())).thenReturn(0);
        when(provisioningService.getMaxTenantsPerCaller()).thenReturn(5);
        var req = req("my-cluster", true);
        req.ec2PricingModel = "reserved";
        Response r = resource.createTenant(req, ctx);
        assertEquals(400, r.getStatus());
    }

    // -------------------------------------------------------------------------
    // idcUserId fallback
    // -------------------------------------------------------------------------

    @Test
    void missingIdcUserId_fallsBackToCallerArn() {
        stubHappyPath();
        when(ctx.getProperty("idcUserId")).thenReturn(null);
        Response r = resource.createTenant(req("my-cluster", true), ctx);
        assertEquals(202, r.getStatus());
        verify(provisioningService).provision(eq("my-cluster"), eq(true),
            eq("arn:aws:iam::123:role/dev"), any(), any(), any(), any(), anyBoolean(), anyInt(), any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static TenantResource.CreateTenantRequest req(String clusterName, Boolean managed) {
        var r = new TenantResource.CreateTenantRequest();
        r.clusterName = clusterName;
        r.managed = managed;
        return r;
    }

    @SuppressWarnings("unchecked")
    private void assertErrorCode(Response r, String expectedCode) {
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals(expectedCode, body.get("__type"));
    }

    private void setField(String name, Object value) throws Exception {
        Field f = TenantResource.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(resource, value);
    }
}
