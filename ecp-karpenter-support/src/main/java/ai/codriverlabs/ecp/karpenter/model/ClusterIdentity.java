package ai.codriverlabs.ecp.karpenter.model;

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
    boolean natGatewayEnabled,    // from ecp-config (written by install script from SSM)
    String karpenterSubnetId,     // public when natGateway=false, private when natGateway=true
    String securityGroupId        // tenant security group for Karpenter nodes
) {}
