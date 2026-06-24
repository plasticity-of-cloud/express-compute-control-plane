# Multi-Tenancy Handling Roadmap

## Status: Naming convention implemented (v1.0.0). Derived tenantId planned.

## Current State

Each tenant gets a single IAM role + instance profile named:
```
eks-d-xpress-tenant-{tenantId}-instance-role
```

Created by `TenantIamService` in the tenant Lambda. Role and instance profile share the same name.
`configure-nodepools.sh` derives the instance profile name using the same convention.

## Instance Profile Naming

A single instance profile per tenant is sufficient for both single and multi-arch deployments.
CPU architecture is a `NodePool` concern (via `kubernetes.io/arch` requirement), not an IAM concern —
the instance profile grants EC2 permissions that are arch-agnostic.

Naming convention (single/multi-arch, single/multi-tenancy):
```
eks-d-xpress-tenant-{tenantId}-instance-role
```

`configure-nodepools.sh` derives this name directly from `$TENANT_ID` in `cluster.env`.

## Tenant ID Derivation for Multi-Tenancy

Using a raw IAM username as `tenantId` is problematic:
- A user deleted and recreated would reuse the same `tenantId`, potentially inheriting stale resources
- IAM usernames are not globally unique across Identity Center directories

### Derived Tenant ID

The `tenantId` should be derived at provisioning time and stored immutably in DynamoDB:

```
tenantId = BASE64URL(SHA256(iamUsername + ":" + createdAt))[:16]
```

`createdAt` is the ISO-8601 UTC timestamp of the `PutItem` call, captured by the Lambda at insertion
and written with a `attribute_not_exists(tenantId)` condition to guarantee it is never overwritten.

Properties:
- **Unique across recreations** — same IAM username + new `createdAt` → different `tenantId`
- **Unique across directories** — Identity Center username collision-resistant via timestamp entropy
- **Stable for tenant lifetime** — `createdAt` is immutable, so `tenantId` never changes
- **Rotatable** — delete + recreate the tenant record to issue a new `tenantId`
- **Human-readable source preserved** — store the original `iamUsername` separately for display/lookup

### DynamoDB Schema Change

```java
public record TenantItem(
    String tenantId,        // derived: BASE64URL(SHA256(iamUsername + ":" + createdAt))[:16]
    String iamUsername,     // original IAM / Identity Center username, for display
    String instanceId,
    String state,
    String phase,
    int progress,
    String publicIp,
    String eipAllocationId,
    String sshKeySecretArn,
    String createdAt,       // set once on PutItem via attribute_not_exists(tenantId)
    String updatedAt,
    String error,
    String ec2PricingModel
) {}
```

### IAM Resource Naming

All IAM resources derived from `tenantId` remain stable:
```
eks-d-xpress-tenant-{tenantId}-instance-role
eks-d-xpress-tenant-{tenantId}-arm64-instance-role    # multi-arch future
eks-d-xpress-tenant-{tenantId}-x86_64-instance-role   # multi-arch future
```

### Usage Flow

1. Tenant creation request arrives with `iamUsername`
2. Lambda captures `createdAt = Instant.now()`, derives `tenantId = BASE64URL(SHA256(iamUsername + ":" + createdAt))[:16]`
3. `PutItem` with `attribute_not_exists(tenantId)` condition — guarantees `createdAt` immutability
4. All downstream resources (IAM, EC2, cluster name) use `tenantId` as the stable key
