package ai.codriverlabs.karpenter.model;

/**
 * Cluster bootstrap fields resolved from in-cluster Kubernetes API sources.
 * Cached with a 5-minute TTL by ClusterIdentityService.
 */
public record ClusterIdentity(
    String clusterName,
    String tenantId,
    String apiServerEndpoint,
    String certificateAuthority,  // base64-encoded PEM
    String serviceCidr,
    String clusterDnsIp,
    boolean natGatewayEnabled     // from eks-dx-config configmap (written by install script from SSM)
) {}
