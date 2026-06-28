# Tenant Authorization & Role Hierarchy

## Overview

EKS-DX uses a role-based authorization model to control who can provision clusters, how many, and for whom. Callers authenticate via IAM SigV4 (direct IAM users, IAM roles, or IAM Identity Center permission sets). The tenant-service Lambda resolves the caller's role from a principal tag (`eks-dx-role`) and enforces permissions and quotas accordingly.

## Role Hierarchy

| Role | Tag Value | Description | Quota | Visibility |
|------|-----------|-------------|-------|------------|
| `EksDXpressUser` | `user` | Engineer self-service — one personal cluster | 1 | Own only |
| `EksDXpressOperator` | `operator` | Platform team — multiple clusters, provision for others | Configurable (default: 50) | Own + provisioned-for-others |
| `EksDXpressAdministrator` | `administrator` | Full access — all clusters, provisioning, management | Unlimited | All |

### Permission Matrix

| Action | User | Operator | Administrator |
|--------|:---:|:---:|:---:|
| Create own cluster | ✓ | ✓ | ✓ |
| Delete own cluster | ✓ | ✓ | ✓ |
| Describe own cluster | ✓ | ✓ | ✓ |
| List own clusters | ✓ | ✓ | ✓ |
| Create cluster for others | ✗ | ✓ | ✓ |
| Delete cluster provisioned for others | ✗ | ✓ | ✓ |
| List all clusters | ✗ | ✗ | ✓ |
| Delete any cluster | ✗ | ✗ | ✓ |
| Manage pod identity associations | ✓ (own) | ✓ (own + provisioned) | ✓ (all) |

### Key Distinctions

- **User** — single developer, one personal cluster. Self-service provisioning and lifecycle.
- **Operator** — platform team member. Provisions and manages clusters on behalf of users. Sees own + provisioned-for-others.
- **Administrator** — full visibility and control over all clusters, tenants, and associations.

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
| IAM Identity Center | `arn:aws:sts::123456789012:assumed-role/AWSReservedSSO_EksDXpressUser_abc123/john@co.com` |
| GitHub Actions OIDC | `arn:aws:sts::123456789012:assumed-role/github-cicd-eks-dx/run-12345` |

### Per-User Identity (idcUserId)

For tenant ID generation and quota enforcement, the system needs a stable per-user identifier:

| Source | Extracted `idcUserId` | Stability |
|--------|----------------------|-----------|
| IDC session (email) | `john@co.com` (from principalId session name) | Stable per user |
| IDC session (UUID) | `94486458-a021-...` (if configured as session name) | Permanent |
| IAM user | `arn:aws:iam::123:user/john.doe` (callerArn) | Stable |

### Role Resolution — Priority Chain

```
1. Session tag (aws:PrincipalTag/eks-dx-role)     ← preferred, zero-cost
2. IAM role/user tag (eks-dx-role)                 ← fallback for direct IAM
3. Role name pattern match (AWSReservedSSO_*)      ← bootstrap/migration only
4. DENY                                            ← no role resolved
```

#### Priority 1: Session Tags (Best Practice)

Session tags are passed through the STS session and available in the request context without any IAM API call. This is the **recommended approach** for IAM Identity Center and OIDC-federated roles.

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

#### Priority 2: IAM Role/User Tags

For direct IAM users or roles without session tags, the Lambda calls `iam:ListUserTags` or `iam:ListRoleTags`. Result is cached for 15 minutes per principal.

#### Priority 3: Role Name Pattern Match (Bootstrap Only)

For Identity Center roles not yet tagged, extract the permission set name:

```
AWSReservedSSO_EksDXpressUser_abc123             → user
AWSReservedSSO_EksDXpressOperator_def456         → operator
AWSReservedSSO_EksDXpressAdministrator_ghi789    → administrator
```

This is a **migration convenience only** — disable via config once all roles are properly tagged.

## Quota Enforcement

```java
String callerRole = resolveRole(callerArn);

int maxClusters = switch (callerRole) {
    case "administrator" -> Integer.MAX_VALUE;
    case "operator"      -> config.getOperatorMax();   // default: 50
    case "user"          -> 1;
    default              -> 0;  // deny
};

String effectiveOwner = (targetOwner != null && isOperatorOrAdmin(callerRole))
    ? targetOwner : callerArn;

int existing = countActiveClustersByOwner(effectiveOwner);
if (existing >= maxClusters) throw new QuotaExceededException(...);
```

## Delete Authorization

```java
boolean allowed = switch (callerRole) {
    case "administrator" -> true;
    case "operator"      -> cluster.ownerArn.equals(callerArn) || callerArn.equals(cluster.provisionedBy);
    case "user"          -> cluster.ownerArn.equals(callerArn);
    default              -> false;
};
```

---

## Authentication Setup

### Option A: Traditional IAM (Testing / Single-Account)

For testing or simple deployments without Identity Center:

```bash
# Create IAM user with minimal permissions
aws iam create-user --user-name eks-dx-test-user
aws iam tag-user --user-name eks-dx-test-user --tags Key=eks-dx-role,Value=user

# Attach inline policy (Function URL + SSM read)
aws iam put-user-policy --user-name eks-dx-test-user \
  --policy-name eks-dx-access \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [
      {
        "Effect": "Allow",
        "Action": "lambda:InvokeFunctionUrl",
        "Resource": "arn:aws:lambda:us-east-1:<account>:function:eks-d-xpress-tenant-service"
      },
      {
        "Effect": "Allow",
        "Action": "ssm:GetParameter",
        "Resource": "arn:aws:ssm:us-east-1:<account>:parameter/eks-d-xpress/control-plane/*"
      }
    ]
  }'

# Create access key and configure CLI
aws iam create-access-key --user-name eks-dx-test-user
aws configure --profile eks-dx-test
```

Test:
```bash
AWS_PROFILE=eks-dx-test eks-dx create-cluster my-dev --wait
```

### Option B: IAM Identity Center (Production / Organization)

**Prerequisites:** Organization-level IAM Identity Center instance (not account-level).

#### 1. Create Permission Sets (CDK-deployed)

The CDK stack creates three permission sets via `AWS::SSO::PermissionSet`:

| Permission Set | Session Tag | Policy |
|----------------|-------------|--------|
| `EksDXpressUser` | `eks-dx-role=user` | `lambda:InvokeFunctionUrl` + `ssm:GetParameter` |
| `EksDXpressOperator` | `eks-dx-role=operator` | Same as User (authorization is server-side) |
| `EksDXpressAdministrator` | `eks-dx-role=administrator` | Same + full API Gateway access |

All three permission sets include:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "lambda:InvokeFunctionUrl",
      "Resource": "arn:aws:lambda:us-east-1:<account>:function:eks-d-xpress-tenant-service"
    },
    {
      "Effect": "Allow",
      "Action": "ssm:GetParameter",
      "Resource": "arn:aws:ssm:us-east-1:<account>:parameter/eks-d-xpress/control-plane/*"
    }
  ]
}
```

Attribute mapping (set on each permission set):
```
https://aws.amazon.com/SAML/Attributes/PrincipalTag:eks-dx-role = <role-value>
```

#### 2. Create Groups in Identity Center

| Group | Assigned Permission Set |
|-------|------------------------|
| `EksDXpressUsers` | `EksDXpressUser` |
| `EksDXpressOperators` | `EksDXpressOperator` |
| `EksDXpressAdministrators` | `EksDXpressAdministrator` |

#### 3. Assign Groups to AWS Account

CDK creates `AWS::SSO::Assignment` resources linking each group to the target account with its permission set.

#### 4. CDK Implementation

```java
// In EksDXpressControlPlaneStack (requires org-level Identity Center)

var instanceArn = StringParameter.valueForStringParameter(this, "/eks-d-xpress/infra/sso-instance-arn");

var userPermissionSet = CfnPermissionSet.Builder.create(this, "EksDXpressUserPS")
    .instanceArn(instanceArn)
    .name("EksDXpressUser")
    .description("EKS-DX self-service user — one personal cluster")
    .sessionDuration("PT1H")
    .inlinePolicy(Map.of(
        "Version", "2012-10-17",
        "Statement", List.of(Map.of(
            "Effect", "Allow",
            "Action", List.of("lambda:InvokeFunctionUrl", "ssm:GetParameter"),
            "Resource", List.of(
                "arn:aws:lambda:" + region + ":" + account + ":function:eks-d-xpress-tenant-service",
                "arn:aws:ssm:" + region + ":" + account + ":parameter/eks-d-xpress/control-plane/*")))))
    .build();

// Repeat for Operator, Administrator...
// Then AWS::SSO::Assignment for each group → account → permission set
```

#### 5. User Login Flow

```bash
# Configure SSO profile
aws configure sso --profile eks-dx
# SSO start URL: https://<your-org>.awsapps.com/start
# Region: us-east-1
# Account: <target-account>
# Role: EksDXpressUser

# Login
aws sso login --profile eks-dx

# Verify identity
aws sts get-caller-identity --profile eks-dx
# Arn: arn:aws:sts::<account>:assumed-role/AWSReservedSSO_EksDXpressUser_abc123/john@company.com

# Use eks-dx CLI
AWS_PROFILE=eks-dx eks-dx create-cluster my-dev --wait
```

#### 6. What the Lambda Receives

```json
{
  "userArn": "arn:aws:sts::864899852480:assumed-role/AWSReservedSSO_EksDXpressUser_abc123/ecosystem@plasticity.cloud",
  "principalId": "AROAXXXXXXXXXXXXXXXXX:ecosystem@plasticity.cloud"
}
```

Extracted by `CallerIdentityFilter`:
- `callerArn`: `arn:aws:iam::864899852480:role/AWSReservedSSO_EksDXpressUser_abc123` (normalized)
- `idcUserId`: `ecosystem@plasticity.cloud` (session name = per-user identity)
- Session tag `eks-dx-role=user` available for authorization

---

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `eks-dx.auth.user-max` | 1 | Max clusters for user role |
| `eks-dx.auth.operator-max` | 50 | Max clusters for operator role |
| `eks-dx.auth.tag-cache-ttl-minutes` | 15 | IAM tag lookup cache TTL |
| `eks-dx.auth.enable-role-name-fallback` | true | Use SSO role name pattern when tag missing |

## Error Responses

| Scenario | HTTP | Code | Message |
|----------|------|------|---------|
| No role resolved | 403 | `AccessDeniedException` | "No eks-dx-role resolved for principal" |
| Quota exceeded | 429 | `QuotaExceededException` | "Quota exceeded: user allows 1 cluster(s), you have 1" |
| Non-operator uses targetOwner | 403 | `AccessDeniedException` | "Only operator or administrator can provision for others" |
| Delete cluster not owned | 403 | `AccessDeniedException` | "Cannot delete cluster owned by another user" |
| Describe cluster not visible | 404 | `NotFoundException` | "Cluster not found" (intentionally 404, not 403) |
