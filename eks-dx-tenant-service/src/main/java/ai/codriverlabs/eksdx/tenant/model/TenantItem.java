package ai.codriverlabs.eksdx.tenant.model;

/**
 * DynamoDB item schema for eks-dx-tenants table.
 *
 * tenantId   — system-derived 8-char hex hash (PK), never user-provided
 * clusterName — user-chosen, workload-visible identifier (GSI: clusterName-index)
 * managed    — true = EKS-D-Xpress provisioned EC2; false = standalone (k3s, microk8s, etc.)
 * idcUserId  — IAM Identity Center user ID used to derive tenantId (audit trail)
 * createdAt  — immutable ISO-8601 UTC timestamp
 *
 * Fields marked "managed only" are null for unmanaged tenants.
 */
public record TenantItem(
    String tenantId,
    String clusterName,
    boolean managed,
    String idcUserId,
    String ownerArn,
    String createdAt,
    String updatedAt,
    // managed only
    String state,
    String phase,
    int progress,
    String instanceId,
    String publicIp,
    String eipAllocationId,
    String sshKeySecretArn,
    String ec2PricingModel,
    String error
) {}
