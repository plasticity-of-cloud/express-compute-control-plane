package ai.codriverlabs.ecp.karpenter.resource;

import ai.codriverlabs.ecp.karpenter.model.ClusterIdentity;
import ai.codriverlabs.ecp.karpenter.model.Ec2NodeClass;
import ai.codriverlabs.ecp.karpenter.model.Ec2NodeClassSpec;
import ai.codriverlabs.ecp.karpenter.service.ClusterIdentityService;
import ai.codriverlabs.ecp.karpenter.service.UserDataMergeService;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Ec2NodeClassWebhookResourceTest {

    @Mock ClusterIdentityService clusterIdentityService;
    @Mock UserDataMergeService userDataMergeService;

    static final ClusterIdentity ID = new ClusterIdentity(
        "my-cluster", "tenant-123", "https://10.0.0.1:6443", "base64ca==", "10.96.0.0/12", "10.96.0.10", false, null, null
    );

    @BeforeEach
    void setUp() {
        when(clusterIdentityService.get()).thenReturn(ID);
    }

    @Test
    void mutate_rewritesAmiFamilyToCustom_forAL2023() {
        var nc = ec2NodeClass("AL2023", null);
        when(userDataMergeService.merge(eq("AL2023"), any(), any())).thenReturn("MIME...");

        var mutated = mutate(nc);

        assertEquals("Custom", mutated.getSpec().getAmiFamily());
    }

    @Test
    void mutate_rewritesAmiFamilyToCustom_forBottlerocket() {
        var nc = ec2NodeClass("Bottlerocket", null);
        when(userDataMergeService.merge(eq("Bottlerocket"), any(), any())).thenReturn("[settings...]");

        var mutated = mutate(nc);

        assertEquals("Custom", mutated.getSpec().getAmiFamily());
    }

    @Test
    void mutate_doesNotRewriteWhenAlreadyCustom() {
        var nc = ec2NodeClass("Custom", "existing-userdata");
        when(userDataMergeService.merge(eq("Custom"), any(), any())).thenReturn(null);

        var mutated = mutate(nc);

        assertEquals("Custom", mutated.getSpec().getAmiFamily());
        verify(userDataMergeService).merge(eq("Custom"), any(), eq(ID));
    }

    @Test
    void mutate_injectsUserData() {
        var nc = ec2NodeClass("AL2023", null);
        when(userDataMergeService.merge(eq("AL2023"), isNull(), eq(ID))).thenReturn("MIME-Version: 1.0\n...");

        var mutated = mutate(nc);

        assertEquals("MIME-Version: 1.0\n...", mutated.getSpec().getUserData());
    }

    @Test
    void mutate_skipsUserDataWhenIdempotent() {
        var nc = ec2NodeClass("AL2023", "already-managed");
        when(userDataMergeService.merge(any(), any(), any())).thenReturn(null);

        var mutated = mutate(nc);

        assertEquals("already-managed", mutated.getSpec().getUserData());
        // amiFamily still rewritten even if userData was idempotent
        assertEquals("Custom", mutated.getSpec().getAmiFamily());
    }

    @Test
    void mutate_noOp_whenClusterIdentityUnavailable() {
        when(clusterIdentityService.get()).thenReturn(null);
        var nc = ec2NodeClass("AL2023", null);

        mutate(nc);

        verifyNoInteractions(userDataMergeService);
        assertEquals("AL2023", nc.getSpec().getAmiFamily()); // unchanged
    }

    @Test
    void mutate_passesOriginalAmiFamilyToMergeService() {
        var nc = ec2NodeClass("Bottlerocket", null);
        when(userDataMergeService.merge(eq("Bottlerocket"), any(), any())).thenReturn("toml");

        mutate(nc);

        // merge() receives original value, not "Custom"
        verify(userDataMergeService).merge(eq("Bottlerocket"), any(), eq(ID));
    }

    @Test
    void mutate_injectsRequiredTags() {
        var nc = ec2NodeClass("AL2023", null);
        when(userDataMergeService.merge(any(), any(), any())).thenReturn("MIME...");

        var mutated = mutate(nc);

        var tags = mutated.getSpec().getTags();
        assertEquals("express-compute", tags.get("Platform"));
        assertEquals("tenant-123", tags.get("Tenant"));
        assertEquals("Karpenter", tags.get("ManagedBy"));
    }

    @Test
    void mutate_preservesCustomerTags() {
        var nc = ec2NodeClass("AL2023", null);
        nc.getSpec().setTags(new java.util.HashMap<>(java.util.Map.of("MyTag", "MyValue")));
        when(userDataMergeService.merge(any(), any(), any())).thenReturn("MIME...");

        var mutated = mutate(nc);

        assertEquals("MyValue", mutated.getSpec().getTags().get("MyTag"));
        assertEquals("express-compute", mutated.getSpec().getTags().get("Platform"));
    }

    @Test
    void mutate_doesNotOverwriteCustomerTenantTag() {
        var nc = ec2NodeClass("AL2023", null);
        nc.getSpec().setTags(new java.util.HashMap<>(java.util.Map.of("Tenant", "customer-override")));
        when(userDataMergeService.merge(any(), any(), any())).thenReturn("MIME...");

        var mutated = mutate(nc);

        assertEquals("customer-override", mutated.getSpec().getTags().get("Tenant"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Exercises the mutation lambda directly — same pattern as WorkloadIdentityMutatorTest. */
    private Ec2NodeClass mutate(Ec2NodeClass nc) {
        var identity = clusterIdentityService.get();
        if (identity == null) return nc;

        if (nc.getSpec() == null) nc.setSpec(new Ec2NodeClassSpec());
        String originalAmiFamily = nc.getSpec().getAmiFamily();

        String merged = userDataMergeService.merge(originalAmiFamily, nc.getSpec().getUserData(), identity);
        if (merged != null) nc.getSpec().setUserData(merged);

        if (!UserDataMergeService.CUSTOM.equals(originalAmiFamily))
            nc.getSpec().setAmiFamily(UserDataMergeService.CUSTOM);

        var tags = nc.getSpec().getTags();
        if (tags == null) { tags = new java.util.HashMap<>(); nc.getSpec().setTags(tags); }
        tags.putIfAbsent("Platform", "express-compute");
        tags.putIfAbsent("Tenant", identity.tenantId());
        tags.putIfAbsent("ManagedBy", "Karpenter");

        return nc;
    }

    private Ec2NodeClass ec2NodeClass(String amiFamily, String userData) {
        var nc = new Ec2NodeClass();
        var meta = new ObjectMeta();
        meta.setName("default");
        nc.setMetadata(meta);
        var spec = new Ec2NodeClassSpec();
        spec.setAmiFamily(amiFamily);
        spec.setUserData(userData);
        nc.setSpec(spec);
        return nc;
    }
}
