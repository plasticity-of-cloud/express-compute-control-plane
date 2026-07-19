# Tenant Authentication & Quota Design

## Overview

Software engineers self-register tenants using the `ecp` CLI from their DCV workstations. CI/CD pipelines (GitHub Actions) create ephemeral tenants for testing. Both authenticate via IAM SigV4, but have different quota limits enforced by the tenant-service Lambda.

## Identity Model

| Persona | IAM Principal | Tag | Quota |
|---------|--------------|-----|-------|
| Software engineer | IAM user or assumed role | `ecp-role=engineer` | 1 tenant |
| CI/CD pipeline | OIDC-federated role (GitHub) | `ecp-role=cicd` | Configurable (default: 10 concurrent) |
| Platform admin | IAM role | `ecp-role=admin` | Unlimited |

## IAM Policy for Engineers (Self-Registration)

Each engineer's IAM user/role needs this policy to use `ecp` CLI:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "EcpTenantAPI",
      "Effect": "Allow",
      "Action": "execute-api:Invoke",
      "Resource": [
        "arn:aws:execute-api:{region}:{account}:{api-id}/{stage}/POST/tenants",
        "arn:aws:execute-api:{region}:{account}:{api-id}/{stage}/GET/tenants/*",
        "arn:aws:execute-api:{region}:{account}:{api-id}/{stage}/DELETE/tenants/*"
      ]
    },
    {
      "Sid": "EcpClusterAPI",
      "Effect": "Allow",
      "Action": "execute-api:Invoke",
      "Resource": [
        "arn:aws:execute-api:{region}:{account}:{api-id}/{stage}/POST/clusters",
        "arn:aws:execute-api:{region}:{account}:{api-id}/{stage}/GET/clusters/*",
        "arn:aws:execute-api:{region}:{account}:{api-id}/{stage}/DELETE/clusters/*",
        "arn:aws:execute-api:{region}:{account}:{api-id}/{stage}/POST/clusters/*/workload-identities",
        "arn:aws:execute-api:{region}:{account}:{api-id}/{stage}/GET/clusters/*/workload-identities",
        "arn:aws:execute-api:{region}:{account}:{api-id}/{stage}/DELETE/clusters/*/workload-identities/*"
      ]
    }
  ]
}
```

The engineer's IAM principal must also be tagged:

```json
{
  "Tags": [
    { "Key": "ecp-role", "Value": "engineer" }
  ]
}
```

## IAM Policy for CI/CD (GitHub Actions OIDC)

GitHub Actions assumes a role via OIDC federation. The role trust policy:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::{account}:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
      },
      "StringLike": {
        "token.actions.githubusercontent.com:sub": "repo:{org}/{repo}:*"
      }
    }
  }]
}
```

The role gets the same `execute-api:Invoke` permissions as engineers, plus the tag:

```json
{ "Key": "ecp-role", "Value": "cicd" }
```

## Quota Enforcement (Lambda-side)

The tenant-service Lambda reads the caller identity from the API Gateway request context and enforces quotas:

```java
// Available in API Gateway event: requestContext.identity.userArn
// Examples:
//   arn:aws:iam::123456789012:user/john.doe
//   arn:aws:sts::123456789012:assumed-role/github-cicd-role/session-name
//   arn:aws:sts::123456789012:assumed-role/engineer-role/john.doe

String callerArn = requestContext.getIdentity().getUserArn();
String callerRole = getCallerEcpRole(callerArn);  // reads ecp-role tag

int maxTenants = switch (callerRole) {
    case "admin" -> Integer.MAX_VALUE;
    case "cicd" -> config.getCicdMaxTenants();  // default: 10
    case "engineer" -> 1;
    default -> 0;  // deny unknown roles
};

int existingCount = countTenantsByOwner(callerArn);
if (existingCount >= maxTenants) {
    throw new QuotaExceededException(
        "Quota exceeded: %s allows %d tenant(s), you have %d".formatted(callerRole, maxTenants, existingCount));
}
```

## DynamoDB: Owner Tracking

Add `ownerArn` to the tenants table to track who created each tenant:

```
ecp-tenants:
┌──────────────────────────────────────────────────────────┐
│ PK: tenantId                                             │
├──────────────────────────────────────────────────────────┤
│ ownerArn: "arn:aws:iam::123:user/john.doe"              │
│ ownerRole: "engineer"                                    │
│ state: "running"                                         │
│ ...                                                      │
└──────────────────────────────────────────────────────────┘
```

Quota check: `Scan` with filter `ownerArn = callerArn AND state != terminated`.

For better performance at scale, add a GSI on `ownerArn`:

```java
// GSI: ownerArn-index (PK: ownerArn, SK: tenantId)
// Query instead of Scan for quota check
```

## Self-Registration Flow

```
Engineer's DCV workstation
    │
    ├─ 1. Engineer has IAM user/role with ecp-role=engineer tag
    │     (provisioned by platform admin or Terraform)
    │
    ├─ 2. ecp configure
    │     → sets endpoint, region in ~/.ecp/config
    │
    ├─ 3. ecp create-tenant my-dev --arch arm64 --ec2-pricing-model spot
    │     │
    │     └─► CLI signs request with SigV4 (uses AWS credentials from env/profile)
    │         │
    │         └─► API Gateway validates SigV4 → forwards to tenant-service Lambda
    │             │
    │             ├─ Lambda reads callerArn from request context
    │             ├─ Lambda reads ecp-role tag from IAM (cached)
    │             ├─ Lambda checks quota: engineer → max 1
    │             ├─ Lambda counts existing tenants for this owner
    │             ├─ If under quota → provision
    │             └─ If over quota → 429 QuotaExceededException
    │
    └─ 4. Engineer gets their single tenant
         ecp describe-tenant my-dev
         ssh -i ~/.ecp/keys/my-dev.pem ec2-user@<ip>
```

## CI/CD Flow (GitHub Actions)

```yaml
jobs:
  integration-test:
    permissions:
      id-token: write  # OIDC
    steps:
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789012:role/github-cicd-ecp
          aws-region: us-east-1

      - name: Create ephemeral tenant
        run: |
          ecp create-tenant ci-${{ github.run_id }} \
            --arch arm64 --ec2-pricing-model spot

      - name: Run tests against tenant cluster
        run: |
          ecp kubeconfig ci-${{ github.run_id }} > ~/.kube/config
          kubectl apply -f manifests/
          kubectl wait --for=condition=ready pod -l app=myapp

      - name: Cleanup
        if: always()
        run: ecp delete-tenant ci-${{ github.run_id }}
```

## API Response on Quota Exceeded

```json
HTTP 429 Too Many Requests

{
  "code": "QuotaExceededException",
  "message": "Quota exceeded: engineer role allows 1 tenant(s), you have 1. Delete existing tenant or contact admin."
}
```

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `ecp.quota.engineer-max-tenants` | 1 | Max tenants per engineer |
| `ecp.quota.cicd-max-tenants` | 10 | Max concurrent tenants per CI/CD role |
| `ecp.quota.admin-max-tenants` | unlimited | No limit for admins |
| `ecp.quota.tag-cache-ttl-minutes` | 15 | Cache duration for IAM tag lookups |

## IAM Permissions for Quota Check

The tenant-service Lambda needs permission to read caller tags:

```java
// CDK: add to tenant Lambda role
tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
    .actions(List.of("iam:GetUser", "iam:GetRole", "iam:ListUserTags", "iam:ListRoleTags"))
    .resources(List.of("arn:aws:iam::*:user/*", "arn:aws:iam::*:role/*"))
    .build());
```

## Provisioning by Platform Admin (Terraform/CDK)

Platform admin creates the IAM infrastructure for engineers:

```hcl
# Per-engineer IAM user (or role if using SSO)
resource "aws_iam_user" "engineer" {
  for_each = var.engineers
  name     = each.key
  tags = {
    "ecp-role" = "engineer"
  }
}

resource "aws_iam_user_policy" "eks_dx_access" {
  for_each = var.engineers
  user     = aws_iam_user.engineer[each.key].name
  name     = "ecp-api-access"
  policy   = data.aws_iam_policy_document.eks_dx_engineer.json
}
```

For GitHub Actions OIDC role:

```hcl
resource "aws_iam_role" "github_cicd" {
  name               = "github-cicd-ecp"
  assume_role_policy = data.aws_iam_policy_document.github_oidc_trust.json
  tags = {
    "ecp-role" = "cicd"
  }
}
```
