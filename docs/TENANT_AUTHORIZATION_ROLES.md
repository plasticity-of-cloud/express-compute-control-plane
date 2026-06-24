# Tenant Authorization & Role Hierarchy

## Overview

EKS-DX uses a role-based authorization model to control who can provision tenants, how many, and for whom. Callers authenticate via IAM SigV4 (direct IAM users, IAM roles, or IAM Identity Center permission sets). The tenant-service Lambda resolves the caller's role from a principal tag (`eks-dx-role`) and enforces permissions and quotas accordingly.

## Role Hierarchy

| Role | Tag Value | Description | Quota | Visibility |
|------|-----------|-------------|-------|------------|
| `EksDxSingleTenant` | `single-tenant` | Engineer self-service — one personal tenant | 1 | Own only |
| `EksDxMultiTenant` | `multi-tenant` | CI/CD or power users — multiple own tenants | Configurable (default: 10) | Own only |
| `EksDxProvisioningOperator` | `provisioning-operator` | Provision tenants on behalf of other users | Configurable (default: 50) | Own + provisioned-for-others |
| `EksDxAdministrator` | `administrator` | Full access — all clusters, tenants, provisioning | Unlimited | All |

### Permission Matrix

| Action | SingleTenant | MultiTenant | ProvisioningOperator | Administrator |
|--------|:---:|:---:|:---:|:---:|
| Create own tenant | ✓ | ✓ | ✓ | ✓ |
| Delete own tenant | ✓ | ✓ | ✓ | ✓ |
| Describe own tenant | ✓ | ✓ | ✓ | ✓ |
| List own tenants | ✓ | ✓ | ✓ | ✓ |
| Create tenant for others | ✗ | ✗ | ✓ | ✓ |
| Delete tenant provisioned for others | ✗ | ✗ | ✓ | ✓ |
| List all tenants | ✗ | ✗ | ✗ | ✓ |
| Delete any tenant | ✗ | ✗ | ✗ | ✓ |
| Manage clusters | ✗ | ✗ | ✗ | ✓ |
| Manage pod identity associations | ✗ | ✗ | ✗ | ✓ |

### Key Distinctions

- **MultiTenant** provisions multiple tenants for **its own account** (e.g., CI/CD creating ephemeral test clusters). Cannot provision for others.
- **ProvisioningOperator** provisions for **other users** (e.g., platform team onboarding engineers). Can see and delete tenants they created on behalf of others, but not tenants owned by unrelated users.
- **Administrator** has full visibility and control over all tenants and clusters.

## Identity Resolution

### Caller Identification

The tenant-service Lambda extracts the caller ARN from the API Gateway request context:

```
requestContext.identity.userArn
```

Examples by identity type:

| Identity Type | ARN Pattern |
|---------------|-------------|
| IAM user | `arn:aws:iam::123456789012:user/john.doe` |
| IAM role (assumed) | `arn:aws:sts::123456789012:assumed-role/EngineerRole/session` |
| IAM Identity Center | `arn:aws:sts::123456789012:assumed-role/AWSReservedSSO_EksDxSingleTenant_abc123/john@co.com` |
| GitHub Actions OIDC | `arn:aws:sts::123456789012:assumed-role/github-cicd-eks-dx/run-12345` |

### Role Resolution — Priority Chain

```
1. Session tag (aws:PrincipalTag/eks-dx-role)     ← preferred, zero-cost
2. IAM role/user tag (eks-dx-role)                 ← fallback for direct IAM
3. Role name pattern match (AWSReservedSSO_*)      ← bootstrap/migration only
4. DENY                                            ← no role resolved
```

#### Priority 1: Session Tags (Best Practice)

Session tags are passed through the STS session and available in the API Gateway request context without any IAM API call. This is the **recommended approach** for IAM Identity Center and OIDC-federated roles.

**Why session tags are preferred:**
- Zero runtime latency — no IAM API call needed
- Works across accounts without cross-account IAM permissions
- Managed centrally in Identity Center (per user or group assignment)
- Follows AWS ABAC (Attribute-Based Access Control) best practices
- Available as `aws:PrincipalTag/eks-dx-role` in IAM policy conditions

**How it works with Identity Center:**

1. Define a custom attribute `eks-dx-role` in Identity Center
2. Map it to a session tag in the permission set's attribute mapping:
   ```json
   {
     "https://aws.amazon.com/SAML/Attributes/PrincipalTag:eks-dx-role": "${user:custom:eks-dx-role}"
   }
   ```
3. Assign the attribute value per user or group in Identity Center
4. When the user federates, the tag arrives in the STS session automatically

**How it works with GitHub Actions OIDC:**

Add a session tag condition to the role's trust policy:
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::{account}:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
        "sts:TransitiveTagKeys": ["eks-dx-role"]
      }
    }
  }]
}
```

And tag the role itself as fallback:
```json
{ "Key": "eks-dx-role", "Value": "multi-tenant" }
```

#### Priority 2: IAM Role/User Tags

For direct IAM users or roles without session tags, the Lambda calls `iam:ListUserTags` or `iam:ListRoleTags`:

```
callerArn contains ":user/"         → iam:ListUserTags(UserName)
callerArn contains ":assumed-role/" → extract role name → iam:ListRoleTags(RoleName)
```

Result is cached for 15 minutes per principal.

#### Priority 3: Role Name Pattern Match (Bootstrap Only)

For Identity Center roles not yet tagged, extract the permission set name:

```
AWSReservedSSO_EksDxSingleTenant_abc123       → single-tenant
AWSReservedSSO_EksDxMultiTenant_def456        → multi-tenant
AWSReservedSSO_EksDxProvisioningOperator_ghi  → provisioning-operator
AWSReservedSSO_EksDxAdministrator_jkl789      → administrator
```

This is a **migration convenience only** — disable via config once all roles are properly tagged.

## Ownership Model

### DynamoDB Schema

```
eks-dx-tenants:
┌──────────────────────────────────────────────────────────────────┐
│ PK: tenantId                                                     │
├──────────────────────────────────────────────────────────────────┤
│ ownerArn: "arn:aws:sts::123:assumed-role/SSO_.../john@co.com"    │
│ ownerRole: "single-tenant"                                       │
│ provisionedBy: null | "arn:aws:sts::123:assumed-role/SSO_.../op" │
│ state: "running" | "provisioning" | "terminated"                 │
│ createdAt: "2026-05-30T23:21:00Z"                                │
│ ...                                                              │
└──────────────────────────────────────────────────────────────────┘

GSI: ownerArn-index (PK: ownerArn, SK: tenantId)
GSI: provisionedBy-index (PK: provisionedBy, SK: tenantId)
```

- `ownerArn` — the effective owner (the user the tenant belongs to)
- `provisionedBy` — set when an operator creates a tenant for someone else (null for self-provisioning)

### Visibility Rules

| Role | List/Describe Query |
|------|-------------------|
| SingleTenant / MultiTenant | `ownerArn = callerArn` |
| ProvisioningOperator | `ownerArn = callerArn OR provisionedBy = callerArn` |
| Administrator | No filter (full scan/query) |

### Provisioning for Others (ProvisioningOperator / Administrator)

Operators specify `targetOwner` in the create request:

```json
POST /tenants
{
  "tenantId": "john-dev",
  "arch": "arm64",
  "targetOwner": "arn:aws:iam::123456789012:user/john.doe",
  ...
}
```

- `ownerArn` is set to `targetOwner`
- `provisionedBy` is set to the caller's ARN
- Quota is checked against `targetOwner`'s existing tenant count
- The operator can later delete this tenant (they provisioned it)

## Quota Enforcement

```java
String callerRole = resolveRole(callerArn);

int maxTenants = switch (callerRole) {
    case "administrator"          -> Integer.MAX_VALUE;
    case "provisioning-operator"  -> config.getOperatorMaxTenants();   // default: 50
    case "multi-tenant"           -> config.getMultiTenantMax();       // default: 10
    case "single-tenant"          -> 1;
    default                       -> 0;  // deny
};

// Determine effective owner
String effectiveOwner;
if (targetOwner != null) {
    if (!isOperatorOrAdmin(callerRole))
        throw new AccessDeniedException("Only provisioning-operator or administrator can provision for others");
    effectiveOwner = targetOwner;
} else {
    effectiveOwner = callerArn;
}

// Check quota against effective owner
int existing = countActiveTenantsByOwner(effectiveOwner);
if (existing >= maxTenants) {
    throw new QuotaExceededException(...);
}
```

## Delete Authorization

```java
String callerRole = resolveRole(callerArn);
Tenant tenant = getTenant(tenantId);

boolean allowed = switch (callerRole) {
    case "administrator" -> true;                                    // can delete anything
    case "provisioning-operator" ->
        tenant.ownerArn.equals(callerArn)                           // own tenant
        || callerArn.equals(tenant.provisionedBy);                  // provisioned by me
    case "single-tenant", "multi-tenant" ->
        tenant.ownerArn.equals(callerArn);                          // own tenant only
    default -> false;
};

if (!allowed) throw new AccessDeniedException("Cannot delete tenant owned by another user");
```

## API Gateway IAM Policies

### EksDxSingleTenant / EksDxMultiTenant

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "execute-api:Invoke",
    "Resource": [
      "arn:aws:execute-api:{region}:{account}:{api-id}/{stage}/POST/tenants",
      "arn:aws:execute-api:{region}:{account}:{api-id}/{stage}/GET/tenants/*",
      "arn:aws:execute-api:{region}:{account}:{api-id}/{stage}/DELETE/tenants/*"
    ]
  }]
}
```

### EksDxProvisioningOperator

Same as above plus list:
```json
{
  "Effect": "Allow",
  "Action": "execute-api:Invoke",
  "Resource": [
    "arn:aws:execute-api:{region}:{account}:{api-id}/{stage}/GET/tenants"
  ]
}
```

Note: The Lambda enforces visibility — operator only sees own + provisioned-for-others, not all tenants.

### EksDxAdministrator

```json
{
  "Effect": "Allow",
  "Action": "execute-api:Invoke",
  "Resource": "arn:aws:execute-api:{region}:{account}:{api-id}/{stage}/*"
}
```

## Lambda IAM Permissions for Role Resolution

```java
// Only needed for Priority 2 (IAM tag fallback). Not needed if all callers use session tags.
tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
    .actions(List.of("iam:ListUserTags", "iam:ListRoleTags"))
    .resources(List.of("arn:aws:iam::*:user/*", "arn:aws:iam::*:role/*"))
    .build());
```

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `eks-dx.auth.single-tenant-max` | 1 | Max tenants for single-tenant role |
| `eks-dx.auth.multi-tenant-max` | 10 | Max tenants for multi-tenant role |
| `eks-dx.auth.operator-max` | 50 | Max tenants for provisioning-operator |
| `eks-dx.auth.tag-cache-ttl-minutes` | 15 | IAM tag lookup cache TTL |
| `eks-dx.auth.enable-role-name-fallback` | true | Use SSO role name pattern when tag missing |

## Error Responses

| Scenario | HTTP | Code | Message |
|----------|------|------|---------|
| No role resolved | 403 | `AccessDeniedException` | "No eks-dx-role resolved for principal" |
| Quota exceeded | 429 | `QuotaExceededException` | "Quota exceeded: single-tenant allows 1 tenant(s), you have 1" |
| Non-operator uses targetOwner | 403 | `AccessDeniedException` | "Only provisioning-operator or administrator can provision for others" |
| Delete tenant not owned/provisioned | 403 | `AccessDeniedException` | "Cannot delete tenant owned by another user" |
| Describe tenant not visible | 404 | `NotFoundException` | "Tenant not found" (intentionally 404, not 403) |

## Identity Center Setup Guide

### 1. Create Permission Sets

In IAM Identity Center, create four permission sets:
- `EksDxSingleTenant`
- `EksDxMultiTenant`
- `EksDxProvisioningOperator`
- `EksDxAdministrator`

Each includes the corresponding API Gateway invoke policy from above.

### 2. Configure Attribute Mapping

For each permission set, add a session tag attribute mapping:

```
Attribute: https://aws.amazon.com/SAML/Attributes/PrincipalTag:eks-dx-role
Value: <role-tag-value>  (e.g., "single-tenant")
```

### 3. Assign Users/Groups

Assign Identity Center groups to permission sets:
- `Engineering` group → `EksDxSingleTenant` permission set
- `CI-CD-Pipelines` group → `EksDxMultiTenant` permission set
- `Platform-Team` group → `EksDxProvisioningOperator` permission set
- `Platform-Admins` group → `EksDxAdministrator` permission set

### 4. Verify

```bash
# After SSO login, verify the session tag is present:
aws sts get-caller-identity
# The eks-dx-role tag is not visible here, but is available to API Gateway
# and in IAM policy conditions as aws:PrincipalTag/eks-dx-role

# Test provisioning:
eks-dx create-tenant my-dev --arch arm64
```

## Migration from TENANT_AUTH_QUOTA.md

The previous design used `engineer`/`cicd`/`admin` tag values. Backward-compatible mapping:

| Old Tag | New Tag | Role |
|---------|---------|------|
| `engineer` | `single-tenant` | EksDxSingleTenant |
| `cicd` | `multi-tenant` | EksDxMultiTenant |
| `admin` | `administrator` | EksDxAdministrator |

Support both during transition. Remove legacy mappings after all principals are re-tagged.
