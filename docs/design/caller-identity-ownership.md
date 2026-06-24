# Caller Identity & Ownership Isolation (v1.1.0 GA)

## Status: Planned for v1.1.0 GA

## Overview

All management API operations extract the caller's identity from the API Gateway request context and enforce ownership isolation. Callers can only see and manage resources they created. A global quota (SSM parameter) limits how many tenants a single caller can provision.

## Scope

Applies to ALL management services:

| Service | Enforcement |
|---------|-------------|
| **tenant-service** | `ownerArn` stored on create; list/describe/delete filtered by caller |
| **mgmt-service** (clusters) | `ownerArn` stored on register; list/describe/deregister filtered by caller |
| **mgmt-service** (associations) | Scoped to the caller's clusters only (can't create associations on another caller's cluster) |

The credential exchange endpoint (`POST /clusters/{name}/assets`) is **not** affected — it's unauthenticated at API Gateway level and validated by JWT.

## Caller Identity Extraction

API Gateway with IAM auth passes the verified caller in the Lambda event:

```java
// Quarkus Lambda HTTP — extract from AwsProxyRequest
String callerArn = request.getRequestContext().getIdentity().getUserArn();
```

| Identity source | `userArn` value |
|-----------------|-----------------|
| IAM user | `arn:aws:iam::123456789012:user/john` |
| Assumed role (CLI, CI/CD) | `arn:aws:sts::123456789012:assumed-role/MyRole/session-name` |
| Identity Center | `arn:aws:sts::123456789012:assumed-role/AWSReservedSSO_PermSet_abc/john@co.com` |
| EC2 instance profile | `arn:aws:sts::123456789012:assumed-role/InstanceRole/i-0abc123` |

### Normalization

For ownership comparison, normalize to a **stable principal** (strip session name for assumed roles):

```java
static String normalizePrincipal(String userArn) {
    // "arn:aws:sts::123:assumed-role/RoleName/session" → "arn:aws:iam::123:role/RoleName"
    if (userArn.contains(":assumed-role/")) {
        String[] parts = userArn.split(":assumed-role/")[1].split("/");
        String account = userArn.split(":")[4];
        return "arn:aws:iam::" + account + ":role/" + parts[0];
    }
    return userArn; // IAM user ARN is already stable
}
```

This ensures the same IAM role always resolves to the same owner, regardless of session name.

## DynamoDB Schema Changes

### eks-dx-tenants

Add `ownerArn` attribute (set on create, immutable):

```
PK: tenantId
  ownerArn: "arn:aws:iam::123456789012:role/MyRole"   ← NEW
  state: "running"
  ...
```

### eks-dx-clusters

Add `ownerArn` attribute (set on register, immutable):

```
PK: clusterName
  ownerArn: "arn:aws:iam::123456789012:role/MyRole"   ← NEW
  issuer: "https://..."
  jwks: "..."
  ...
```

### GSI for listing

Add GSI `owner-index` on both tables:
- PK: `ownerArn`
- SK: `tenantId` (tenants) / `clusterName` (clusters)

This enables efficient `list my resources` queries without full table scans.

## Enforcement Rules

### Create

```java
// On create, stamp ownership
item.put("ownerArn", callerArn);
```

### List

```java
// Query by owner GSI — only returns caller's resources
QueryRequest.builder()
    .tableName(table)
    .indexName("owner-index")
    .keyConditionExpression("ownerArn = :owner")
    .expressionAttributeValues(Map.of(":owner", AttributeValue.fromS(callerArn)))
```

### Describe / Delete

```java
// After fetching, verify ownership
Tenant tenant = getTenant(tenantId);
if (!callerArn.equals(tenant.ownerArn))
    throw new NotFoundException("Tenant not found: " + tenantId);  // 404 not 403 (don't leak existence)
```

### Associations

Associations are scoped to clusters. The check is:
1. Resolve cluster's `ownerArn`
2. If `cluster.ownerArn != callerArn` → 404 "Cluster not found"
3. Otherwise proceed with association CRUD

This means you can only create/list/delete associations on clusters you own.

## Quota — Global SSM Parameter

A single SSM parameter controls the maximum tenants per caller:

```
/eks-dx/quota/max-tenants-per-caller = 5
```

### CDK

```java
StringParameter.Builder.create(this, "MaxTenantsPerCaller")
    .parameterName("/eks-dx/quota/max-tenants-per-caller")
    .stringValue("5")
    .description("Maximum tenants a single IAM identity can provision")
    .build();
```

### Enforcement (tenant-service)

```java
@ConfigProperty(name = "eks-dx.quota.max-tenants-per-caller", defaultValue = "5")
int maxTenantsPerCaller;

void enforceQuota(String callerArn) {
    int existing = countTenantsByOwner(callerArn);
    if (existing >= maxTenantsPerCaller)
        throw new QuotaExceededException(
            "Quota exceeded: maximum " + maxTenantsPerCaller + " tenants per caller, you have " + existing);
}
```

The Lambda reads this at startup from SSM (via Quarkus config). Changing the SSM value requires a Lambda redeploy or cold start to take effect.

### Override per caller (post-GA)

For GA, quota is global (same limit for everyone). Post-GA, per-caller overrides can be stored in DynamoDB or as caller-specific SSM parameters.

## Error Responses

| Scenario | HTTP | Code | Message |
|----------|------|------|---------|
| Tenant/cluster not owned by caller | 404 | `NotFoundException` | "Tenant not found" / "Cluster not found" |
| Quota exceeded | 429 | `QuotaExceededException` | "Quota exceeded: maximum N tenants per caller" |
| Association on non-owned cluster | 404 | `NotFoundException` | "Cluster not found" |

Note: 404 (not 403) to avoid leaking resource existence to non-owners.

## Implementation Checklist

- [ ] **Shared**: `CallerIdentityExtractor` utility — extracts + normalizes caller ARN from request context
- [ ] **tenant-service**: Store `ownerArn` on create; filter list/describe/delete by owner
- [ ] **tenant-service**: Add `owner-index` GSI to eks-dx-tenants table (CDK)
- [ ] **tenant-service**: Quota enforcement (`/eks-dx/quota/max-tenants-per-caller`)
- [ ] **mgmt-service**: Store `ownerArn` on cluster registration; filter by owner
- [ ] **mgmt-service**: Add `owner-index` GSI to eks-dx-clusters table (CDK)
- [ ] **mgmt-service**: Scope association CRUD to owned clusters only
- [ ] **CDK**: Add SSM parameter `/eks-dx/quota/max-tenants-per-caller`
- [ ] **CDK**: Add GSIs on both DynamoDB tables

## Relationship to Full Role Hierarchy (Post-GA)

This document implements the **minimum viable authorization** for GA. The full role hierarchy (`docs/design/tenant/authorization-roles.md`) adds:
- Named roles (SingleTenant, MultiTenant, ProvisioningOperator, Administrator)
- Per-role quotas
- Provisioning for others (`targetOwner`)
- Session tag-based role resolution
- Administrator full-visibility bypass

Ownership isolation is the foundation that the role hierarchy builds on — `ownerArn` and the GSI remain the same.
