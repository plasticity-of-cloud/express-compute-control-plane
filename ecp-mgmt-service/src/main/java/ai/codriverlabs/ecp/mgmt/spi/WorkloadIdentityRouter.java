package ai.codriverlabs.ecp.mgmt.spi;

import ai.codriverlabs.ecp.mgmt.service.DynamoDbClusterService;
import ai.codriverlabs.ecp.model.ClusterType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Routes workload identity operations to the correct provider
 * based on the cluster's registered type.
 *
 * Resolution order:
 * 1. Look up cluster in DynamoDB
 * 2. Read clusterType attribute (defaults to MANAGED if absent — backward compatible)
 * 3. Return matching provider from the discovered set
 *
 * Providers register themselves via CDI (@ApplicationScoped + @Named).
 * Community repo: ManagedClusterProvider only.
 * PRO repo: adds EksManagedProvider, EcsOverlayProvider.
 */
@ApplicationScoped
public class WorkloadIdentityRouter {

    private static final Logger LOG = Logger.getLogger(WorkloadIdentityRouter.class);

    @Inject
    DynamoDbClusterService clusterService;

    @Inject
    Instance<WorkloadIdentityProvider> providers;

    /**
     * Resolve the correct provider for the given cluster.
     *
     * @param clusterName the registered cluster name
     * @return the provider that handles this cluster's associations
     * @throws IllegalArgumentException if cluster is not registered or no provider found
     */
    public WorkloadIdentityProvider resolve(String clusterName) {
        ClusterType type = clusterService.getClusterType(clusterName);
        LOG.debugf("Resolved cluster type for '%s': %s", clusterName, type);

        for (WorkloadIdentityProvider provider : providers) {
            if (provider.type() == type) {
                return provider;
            }
        }

        // Fallback: MANAGED provider handles unknown types for backward compatibility
        for (WorkloadIdentityProvider provider : providers) {
            if (provider.type() == ClusterType.MANAGED) {
                LOG.warnf("No provider for cluster type %s, falling back to MANAGED", type);
                return provider;
            }
        }

        throw new IllegalStateException("No WorkloadIdentityProvider found for cluster type: " + type);
    }
}
