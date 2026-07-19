package ai.codriverlabs.ecp.mgmt.spi;

import ai.codriverlabs.ecp.mgmt.service.DynamoDbClusterService;
import ai.codriverlabs.ecp.model.ClusterType;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkloadIdentityRouterTest {

    @Mock
    DynamoDbClusterService clusterService;

    @Mock
    WorkloadIdentityProvider eksDxProvider;

    @Mock
    WorkloadIdentityProvider eksManagedProvider;

    @Mock
    Instance<WorkloadIdentityProvider> providers;

    WorkloadIdentityRouter router;

    @BeforeEach
    void setUp() {
        when(eksDxProvider.type()).thenReturn(ClusterType.MANAGED);
        when(eksManagedProvider.type()).thenReturn(ClusterType.EKS_NATIVE);

        // Simulate CDI Instance<> iteration
        when(providers.iterator()).thenAnswer(inv ->
            List.of(eksDxProvider, eksManagedProvider).iterator());

        router = new WorkloadIdentityRouter();
        setField(router, "clusterService", clusterService);
        setField(router, "providers", providers);
    }

    @Test
    void shouldRouteToManagedClusterProviderForEcpCluster() {
        when(clusterService.getClusterType("my-eksd-cluster")).thenReturn(ClusterType.MANAGED);

        var provider = router.resolve("my-eksd-cluster");

        assertSame(eksDxProvider, provider);
    }

    @Test
    void shouldRouteToEksManagedProviderForManagedCluster() {
        when(clusterService.getClusterType("eks-production")).thenReturn(ClusterType.EKS_NATIVE);

        var provider = router.resolve("eks-production");

        assertSame(eksManagedProvider, provider);
    }

    @Test
    void shouldFallBackToEcpForUnknownType() {
        // ECS not registered — should fall back to MANAGED
        when(clusterService.getClusterType("ecs-cluster")).thenReturn(ClusterType.ECS);

        var provider = router.resolve("ecs-cluster");

        assertSame(eksDxProvider, provider);
    }

    @Test
    void shouldRouteWithOnlyManagedClusterProvider() {
        // Community mode: only MANAGED provider available
        when(providers.iterator()).thenAnswer(inv ->
            List.of(eksDxProvider).iterator());
        when(clusterService.getClusterType("my-cluster")).thenReturn(ClusterType.MANAGED);

        var provider = router.resolve("my-cluster");

        assertSame(eksDxProvider, provider);
    }

    @Test
    void shouldFallBackWhenRequestedProviderNotAvailable() {
        // Community mode: only MANAGED, but cluster claims EKS_NATIVE → fallback
        when(providers.iterator()).thenAnswer(inv ->
            List.of(eksDxProvider).iterator());
        when(clusterService.getClusterType("eks-cluster")).thenReturn(ClusterType.EKS_NATIVE);

        var provider = router.resolve("eks-cluster");

        assertSame(eksDxProvider, provider);
    }

    @Test
    void shouldThrowWhenClusterNotRegistered() {
        when(clusterService.getClusterType("nonexistent"))
            .thenThrow(new IllegalArgumentException("Cluster not registered: nonexistent"));

        assertThrows(IllegalArgumentException.class, () -> router.resolve("nonexistent"));
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }
}
