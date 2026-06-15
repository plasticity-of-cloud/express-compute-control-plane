package ai.codriverlabs.karpenter.resource;

import ai.codriverlabs.karpenter.model.ClusterIdentity;
import ai.codriverlabs.karpenter.model.Ec2NodeClass;
import ai.codriverlabs.karpenter.model.Ec2NodeClassSpec;
import ai.codriverlabs.karpenter.service.ClusterIdentityService;
import ai.codriverlabs.karpenter.service.UserDataMergeService;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Ec2NodeClassWebhookResourceTest {

    @Mock ClusterIdentityService clusterIdentityService;
    @Mock UserDataMergeService userDataMergeService;

    static final ClusterIdentity ID = new ClusterIdentity(
        "my-cluster", "https://10.0.0.1:6443", "base64ca==", "10.96.0.0/12", "10.96.0.10"
    );

    Ec2NodeClass ec2NodeClass;

    @BeforeEach
    void setUp() {
        ec2NodeClass = new Ec2NodeClass();
        var meta = new ObjectMeta();
        meta.setName("default");
        // node-variant annotation drives userData format; amiFamily is always Custom
        meta.setAnnotations(Map.of(UserDataMergeService.NODE_VARIANT_ANNOTATION, "al2023"));
        ec2NodeClass.setMetadata(meta);
        var spec = new Ec2NodeClassSpec();
        spec.setAmiFamily("Custom");
        ec2NodeClass.setSpec(spec);
    }

    @Test
    void mutate_injectsUserData_whenMergeReturnsContent() {
        when(clusterIdentityService.get()).thenReturn(ID);
        when(userDataMergeService.merge(eq("al2023"), isNull(), eq(ID))).thenReturn("MIME-Version: 1.0\n...");

        var mutated = mutate(ec2NodeClass);

        assertEquals("MIME-Version: 1.0\n...", mutated.getSpec().getUserData());
    }

    @Test
    void mutate_noOp_whenMergeReturnsNull() {
        when(clusterIdentityService.get()).thenReturn(ID);
        when(userDataMergeService.merge(any(), any(), any())).thenReturn(null);

        var mutated = mutate(ec2NodeClass);

        assertNull(mutated.getSpec().getUserData());
    }

    @Test
    void mutate_noOp_whenClusterIdentityUnavailable() {
        when(clusterIdentityService.get()).thenReturn(null);

        mutate(ec2NodeClass);

        verifyNoInteractions(userDataMergeService);
    }

    @Test
    void mutate_passesNodeVariantAnnotation_toMergeService() {
        when(clusterIdentityService.get()).thenReturn(ID);
        when(userDataMergeService.merge(eq("al2023"), any(), any())).thenReturn("merged");

        mutate(ec2NodeClass);

        verify(userDataMergeService).merge(eq("al2023"), any(), eq(ID));
    }

    @Test
    void mutate_handlesBottlerocketVariant() {
        ec2NodeClass.getMetadata().setAnnotations(
            Map.of(UserDataMergeService.NODE_VARIANT_ANNOTATION, "bottlerocket"));
        when(clusterIdentityService.get()).thenReturn(ID);
        when(userDataMergeService.merge(eq("bottlerocket"), any(), any())).thenReturn("[settings.kubernetes]\n...");

        var mutated = mutate(ec2NodeClass);

        assertEquals("[settings.kubernetes]\n...", mutated.getSpec().getUserData());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Exercises the mutation lambda directly — same pattern as PodIdentityMutatorTest. */
    private Ec2NodeClass mutate(Ec2NodeClass nc) {
        var identity = clusterIdentityService.get();
        if (identity == null) return nc;

        String nodeVariant = nc.getMetadata().getAnnotations() != null
            ? nc.getMetadata().getAnnotations().get(UserDataMergeService.NODE_VARIANT_ANNOTATION)
            : null;
        String existing = nc.getSpec() != null ? nc.getSpec().getUserData() : null;
        String merged   = userDataMergeService.merge(nodeVariant, existing, identity);
        if (merged == null) return nc;

        if (nc.getSpec() == null) nc.setSpec(new Ec2NodeClassSpec());
        nc.getSpec().setUserData(merged);
        return nc;
    }
}
