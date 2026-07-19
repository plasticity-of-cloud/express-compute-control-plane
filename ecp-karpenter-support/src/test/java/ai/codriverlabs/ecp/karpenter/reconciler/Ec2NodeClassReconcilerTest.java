package ai.codriverlabs.ecp.karpenter.reconciler;

import ai.codriverlabs.ecp.karpenter.model.Ec2NodeClass;
import ai.codriverlabs.ecp.karpenter.service.ValidationConditionService;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Ec2NodeClassReconcilerTest {

    @Mock ValidationConditionService validationConditionService;
    @Mock KubernetesClient client;

    Ec2NodeClassReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new Ec2NodeClassReconciler();
        reconciler.validationConditionService = validationConditionService;
        reconciler.client = client;
    }

    @Test
    void reconcile_noOp_whenAlreadySucceeded() {
        var nc = ec2NodeClass("default");
        mockClientGet("default", nc);
        when(validationConditionService.isValidationSucceeded(any())).thenReturn(true);

        reconciler.reconcile("default");

        verify(validationConditionService, never()).setValidationSucceeded(any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reconcile_patchesStatus_whenConditionMissing() {
        var nc = ec2NodeClass("default");
        when(validationConditionService.isValidationSucceeded(any())).thenReturn(false);

        var op = mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        Resource getResource = mock(Resource.class);
        Resource patchResource = mock(Resource.class);
        when(client.resources(Ec2NodeClass.class)).thenReturn(op);
        when(op.withName("default")).thenReturn(getResource);
        when(getResource.get()).thenReturn(nc);
        when(op.resource(any())).thenReturn(patchResource);

        reconciler.reconcile("default");

        verify(validationConditionService).setValidationSucceeded(any());
        verify(patchResource).patchStatus();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockClientGet(String name, Ec2NodeClass nc) {
        var op = mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        Resource resource = mock(Resource.class);
        when(client.resources(Ec2NodeClass.class)).thenReturn(op);
        when(op.withName(name)).thenReturn(resource);
        when(resource.get()).thenReturn(nc);
    }

    private Ec2NodeClass ec2NodeClass(String name) {
        var nc = new Ec2NodeClass();
        var meta = new ObjectMeta();
        meta.setName(name);
        meta.setGeneration(1L);
        nc.setMetadata(meta);
        return nc;
    }
}
