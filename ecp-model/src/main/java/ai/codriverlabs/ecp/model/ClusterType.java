package ai.codriverlabs.ecp.model;

/**
 * Identifies how a cluster's workload identities are managed.
 * Used by the Workload Identity SPI to route association operations
 * to the correct backend provider.
 */
public enum ClusterType {

    /**
     * Platform-managed cluster (EKS-D, k3s, microk8s — single-instance control plane).
     * Associations stored in DynamoDB. Credential exchange via ECP Lambda.
     */
    MANAGED,

    /**
     * Self-managed cluster (user registers JWKS, manages their own infrastructure).
     * Associations stored in DynamoDB. Credential exchange via ECP Lambda.
     */
    SELF_MANAGED,

    /**
     * Native EKS managed cluster.
     * Associations proxied to aws eks create-pod-identity-association API.
     */
    EKS_NATIVE,

    /**
     * ECS cluster with identity overlay.
     * Associations stored in DynamoDB + synced to identity sidecar.
     */
    ECS;

    /**
     * Parse from string, defaulting to MANAGED for backward compatibility.
     */
    public static ClusterType fromString(String value) {
        if (value == null || value.isBlank()) {
            return MANAGED;
        }
        try {
            return valueOf(value.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return MANAGED;
        }
    }
}
