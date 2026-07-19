package ai.codriverlabs.ecp.mgmt.spi;

import ai.codriverlabs.ecp.model.ClusterType;

import java.util.List;
import java.util.Map;

/**
 * Service Provider Interface for workload identity management.
 * Each cluster type has a different backend provider.
 *
 * Implementations:
 * - ManagedClusterProvider: stores in DynamoDB (existing behavior, non-EKS clusters)
 * - EksManagedProvider: proxies to native aws eks pod-identity-association APIs
 * - EcsOverlayProvider: stores in DynamoDB + syncs to k3s CRDs (Phase 2)
 */
public interface WorkloadIdentityProvider {

    /**
     * The cluster type this provider handles.
     */
    ClusterType type();

    /**
     * Create a workload identity.
     *
     * @return Map containing at minimum: associationId, clusterName, namespace, serviceAccount, roleArn, createdAt
     */
    Map<String, String> createAssociation(String clusterName, String namespace,
                                           String serviceAccount, String roleArn);

    /**
     * List associations for a cluster, optionally filtered by namespace and/or service account.
     */
    List<Map<String, String>> listAssociations(String clusterName,
                                                String namespace, String serviceAccount);

    /**
     * Describe a specific association by ID.
     *
     * @return Association details, or null if not found
     */
    Map<String, String> describeAssociation(String clusterName, String associationId);

    /**
     * Delete an association by ID.
     */
    void deleteAssociation(String clusterName, String associationId);
}
