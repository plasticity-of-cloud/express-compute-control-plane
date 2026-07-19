# Tenant Authorization & Role Hierarchy

## Overview

Express Compute uses a role-based authorization model to control who can provision clusters, how many, and for whom. Callers authenticate via IAM SigV4 (direct IAM users, IAM roles, or IAM Identity Center permission sets). The tenant-service Lambda resolves the caller's role from a principal tag (`ecp-role`) and enforces permissions and quotas accordingly.

## Role Hierarchy

| Role | Tag Value | Description | Quota | Visibility |
|------|-----------|-------------|-------|------------|
| `ECPpressUser` | `user` | Engineer self-service — one personal cluster | 1 | Own only |
| `ECPpressOperator` | `operator` | CI/CD or M2M accounts — multiple own clusters (limit set in CDK) | Configurable (CDK parameter) | Own only |
| `ECPpressAdministrator` | `administrator` | Full access — all clusters, provisioning for others, management | Configurable (CDK parameter) | All |

### Permission Matrix

| Action | User | Operator | Administrator |
|--------|:---:|:---:|:---:|
| Create own cluster | ✓ | ✓ | ✓ |
| Delete own cluster | ✓ | ✓ | ✓ |
| Describe own cluster | ✓ | ✓ | ✓ |
| List own clusters | ✓ | ✓ | ✓ |
| Create cluster for others | ✗ | ✗ | ✓ |
| Delete cluster provisioned for others | ✗ | ✗ | ✓ |
| List all clusters | ✗ | ✗ | ✓ |
| Delete any cluster | ✗ | ✗ | ✓ |
| Manage workload identitys | ✓ (own) | ✓ (own) | ✓ (all) |

### Key Distinctions

- **User** — single developer, one personal cluster. Self-service provisioning and lifecycle.
- **Operator** — CI/CD pipeline or M2M service account. Provisions multiple clusters **for itself** (e.g., ephemeral test environments). Limit is set as a CDK parameter. Cannot provision for other identities.
- **Administrator** — platform team. Full visibility and control, including provisioning on behalf of other users.

## Identity Resolution

### Caller Identification

The tenant-service Lambda extracts the caller identity from the Function URL request context:

```
requestContext.authorizer.iam.userArn
requestContext.authorizer.iam.principalId
```

Examples by identity type:

| Identity Type | ARN Pattern |
|---------------|-------------|
| IAM user | `arn:aws:iam::123456789012:user/john.doe` |
| IAM role (assumed) | `arn:aws:sts::123456789012:assumed-role/EngineerRole/session` |
| IAM Identity Center | `arn:aws:sts::123456789012:assumed-role/AWSReservedSSO_ECPpressUser_abc123/john@co.com` |
| GitHub Actions OIDC | `arn:aws:sts::123456789012:assumed-role/github-cicd-ecp/run-12345` |

### Per-User Identity (idcUserId)

For tenant ID generation and quota enforcement, the system needs a stable per-user identifier:

| Source | Extracted `idcUserId` | Stability |
|--------|----------------------|-----------|
| IDC session (email) | `john@co.com` (from principalId session name) | Stable per user |
| IDC session (UUID) | `94486458-a021-...` (if configured as session name) | Permanent |
| IAM user | `arn:aws:iam::123:user/john.doe` (callerArn) | Stable |

### Role Resolution — Priority Chain

```
1. Session tag (aws:PrincipalTag/ecp-role)     ← preferred, zero-cost
2. IAM role/user tag (ecp-role)                 ← fallback for direct IAM
3. Role name pattern match (AWSReservedSSO_*)      ← bootstrap/migration only
4. DENY                                            ← no role resolved
```

#### Priority 1: Session Tags (Best Practice)

Session tags are passed through the STS session and available in the request context without any IAM API call. This is the **recommended approach** for IAM Identity Center and OIDC-federated roles.

**How it works with Identity Center:**

1. Define a custom attribute `ecp-role` in Identity Center
2. Map it to a session tag in the permission set's attribute mapping:
   ```json
   {
     "https://aws.amazon.com/SAML/Attributes/PrincipalTag:ecp-role": "${user:custom:ecp-role}"
   }
   ```
3. Assign the attribute value per user or group in Identity Center
4. When the user federates, the tag arrives in the STS session automatically

#### Priority 2: IAM Role/User Tags

For direct IAM users or roles without session tags, the Lambda calls `iam:ListUserTags` or `iam:ListRoleTags`. Result is cached for 15 minutes per principal.

#### Priority 3: Role Name Pattern Match (Bootstrap Only)

For Identity Center roles not yet tagged, extract the permission set name:

```
AWSReservedSSO_ECPpressUser_abc123             → user
AWSReservedSSO_ECPpressOperator_def456         → operator
AWSReservedSSO_ECPpressAdministrator_ghi789    → administrator
```

This is a **migration convenience only** — disable via config once all roles are properly tagged.

## Quota Enforcement

```java
String callerRole = resolveRole(callerArn);

int maxClusters = switch (callerRole) {
    case "administrator" -> config.getAdminMax();      // CDK parameter, default: 10
    case "operator"      -> config.getOperatorMax();   // CDK parameter, default: 10
    case "user"          -> 1;
    default              -> 0;  // deny
};

// Operators provision for themselves only; only administrators can use targetOwner
String effectiveOwner;
if (targetOwner != null) {
    if (!"administrator".equals(callerRole))
        throw new AccessDeniedException("Only administrator can provision for others");
    effectiveOwner = targetOwner;
} else {
    effectiveOwner = callerArn;
}

int existing = countActiveClustersByOwner(effectiveOwner);
if (existing >= maxClusters) throw new QuotaExceededException(...);
```

## Delete Authorization

```java
boolean allowed = switch (callerRole) {
    case "administrator" -> true;
    case "operator", "user" -> cluster.ownerArn.equals(callerArn);  // own only
    default -> false;
};
```

---

## Authentication Setup

### Option A: Traditional IAM (Testing / Single-Account)

For testing or simple deployments without Identity Center:

```bash
# Create IAM user with minimal permissions
aws iam create-user --user-name ecp-test-user
aws iam tag-user --user-name ecp-test-user --tags Key=ecp-role,Value=user

# Attach inline policy (Function URL + SSM read)
aws iam put-user-policy --user-name ecp-test-user \
  --policy-name ecp-access \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [
      {
        "Effect": "Allow",
        "Action": "lambda:InvokeFunctionUrl",
        "Resource": "arn:aws:lambda:us-east-1:<account>:function:express-compute-tenant-service"
      },
      {
        "Effect": "Allow",
        "Action": "ssm:GetParameter",
        "Resource": "arn:aws:ssm:us-east-1:<account>:parameter/express-compute/control-plane/*"
      }
    ]
  }'

# Create access key and configure CLI
aws iam create-access-key --user-name ecp-test-user
aws configure --profile ecp-test
```

Test:
```bash
AWS_PROFILE=ecp-test ecp create-cluster my-dev --wait
```

### Option B: IAM Identity Center (Production / Organization)

**Prerequisites:** Organization-level IAM Identity Center instance (not account-level).

#### 1. Create Permission Sets (CDK-deployed)

The CDK stack creates three permission sets via `AWS::SSO::PermissionSet`:

| Permission Set | Session Tag | Policy |
|----------------|-------------|--------|
| `ECPpressUser` | `ecp-role=user` | `lambda:InvokeFunctionUrl` + `ssm:GetParameter` |
| `ECPpressOperator` | `ecp-role=operator` | Same as User (authorization is server-side) |
| `ECPpressAdministrator` | `ecp-role=administrator` | Same + full API Gateway access |

All three permission sets include:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "lambda:InvokeFunctionUrl",
      "Resource": "arn:aws:lambda:us-east-1:<account>:function:express-compute-tenant-service"
    },
    {
      "Effect": "Allow",
      "Action": "ssm:GetParameter",
      "Resource": "arn:aws:ssm:us-east-1:<account>:parameter/express-compute/control-plane/*"
    }
  ]
}
```

Attribute mapping (set on each permission set):
```
https://aws.amazon.com/SAML/Attributes/PrincipalTag:ecp-role = <role-value>
```

#### 2. Create Groups in Identity Center

| Group | Assigned Permission Set |
|-------|------------------------|
| `ECPpressUsers` | `ECPpressUser` |
| `ECPpressOperators` | `ECPpressOperator` |
| `ECPpressAdministrators` | `ECPpressAdministrator` |

#### 3. Assign Groups to AWS Account

CDK creates `AWS::SSO::Assignment` resources linking each group to the target account with its permission set.

#### 4. CDK Implementation

```java
// In ExpressComputeControlPlaneStack (requires org-level Identity Center)

var instanceArn = StringParameter.valueForStringParameter(this, "/express-compute/infra/sso-instance-arn");

var userPermissionSet = CfnPermissionSet.Builder.create(this, "ECPpressUserPS")
    .instanceArn(instanceArn)
    .name("ECPpressUser")
    .description("Express Compute self-service user — one personal cluster")
    .sessionDuration("PT1H")
    .inlinePolicy(Map.of(
        "Version", "2012-10-17",
        "Statement", List.of(Map.of(
            "Effect", "Allow",
            "Action", List.of("lambda:InvokeFunctionUrl", "ssm:GetParameter"),
            "Resource", List.of(
                "arn:aws:lambda:" + region + ":" + account + ":function:express-compute-tenant-service",
                "arn:aws:ssm:" + region + ":" + account + ":parameter/express-compute/control-plane/*")))))
    .build();

// Repeat for Operator, Administrator...
// Then AWS::SSO::Assignment for each group → account → permission set
```

#### 5. User Login Flow

```bash
# Configure SSO profile
aws configure sso --profile ecp
# SSO start URL: https://<your-org>.awsapps.com/start
# Region: us-east-1
# Account: <target-account>
# Role: ECPpressUser

# Login
aws sso login --profile ecp

# Verify identity
aws sts get-caller-identity --profile ecp
# Arn: arn:aws:sts::<account>:assumed-role/AWSReservedSSO_ECPpressUser_abc123/john@company.com

# Use ecp CLI
AWS_PROFILE=ecp ecp create-cluster my-dev --wait
```

#### 6. What the Lambda Receives

```json
{
  "userArn": "arn:aws:sts::864899852480:assumed-role/AWSReservedSSO_ECPpressUser_abc123/ecosystem@plasticity.cloud",
  "principalId": "AROAXXXXXXXXXXXXXXXXX:ecosystem@plasticity.cloud"
}
```

Extracted by `CallerIdentityFilter`:
- `callerArn`: `arn:aws:iam::864899852480:role/AWSReservedSSO_ECPpressUser_abc123` (normalized)
- `idcUserId`: `ecosystem@plasticity.cloud` (session name = per-user identity)
- Session tag `ecp-role=user` available for authorization

---

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `ecp.auth.user-max` | 1 | Max clusters for user role |
| `ecp.auth.operator-max` | 10 | Max clusters for operator role (CDK parameter) |
| `ecp.auth.admin-max` | 10 | Max clusters for administrator role (CDK parameter) |
| `ecp.auth.tag-cache-ttl-minutes` | 15 | IAM tag lookup cache TTL |
| `ecp.auth.enable-role-name-fallback` | true | Use SSO role name pattern when tag missing |

## Error Responses

| Scenario | HTTP | Code | Message |
|----------|------|------|---------|
| No role resolved | 403 | `AccessDeniedException` | "No ecp-role resolved for principal" |
| Quota exceeded | 429 | `QuotaExceededException` | "Quota exceeded: user allows 1 cluster(s), you have 1" |
| Non-operator uses targetOwner | 403 | `AccessDeniedException` | "Only operator or administrator can provision for others" |
| Delete cluster not owned | 403 | `AccessDeniedException` | "Cannot delete cluster owned by another user" |
| Describe cluster not visible | 404 | `NotFoundException` | "Cluster not found" (intentionally 404, not 403) |
