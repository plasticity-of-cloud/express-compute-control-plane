package ai.codriverlabs.eksdx.tenant.model;

/**
 * DynamoDB item schema for eks-dx-tenants table.
 */
public record TenantItem(
    String tenantId,
    String instanceId,
    String state,
    String phase,
    int progress,
    String publicIp,
    String sshKeySecretArn,
    String updatedAt,
    String error
) {}
