# Trust Policy Management — Implementation Design

## Overview

Express Compute manages trust policy statements on target IAM roles automatically when workload identitys are created or deleted. The mgmt-service Lambda appends/removes scoped trust statements, gated by the `ecp-managed=true` resource tag on the target role.

## Architecture

```
POST /clusters/{name}/workload-identities
  │
  └─ mgmt-service Lambda
      ├── validateRoleExists()        — iam:GetRole
      ├── isRoleTagged()              — iam:ListRoleTags (checks ecp-managed=true)
      ├── addTrustStatement()         — iam:UpdateAssumeRolePolicy (append-only)
      └── store in DynamoDB

DELETE /clusters/{name}/workload-identities/{id}
  │
  └─ mgmt-service Lambda
      ├── removeTrustStatement()      — iam:UpdateAssumeRolePolicy (targeted removal by Sid)
      └── delete from DynamoDB
```

## Trust Statement Format

Each association produces one trust statement with a deterministic Sid:

```json
{
  "Sid": "AllowECP<cluster><namespace><sa>",
  "Effect": "Allow",
  "Principal": {
    "AWS": "arn:aws:iam::<control-plane-account>:role/ECPCredentialBroker"
  },
  "Action": ["sts:AssumeRole", "sts:TagSession", "sts:SetSourceIdentity"],
  "Condition": {
    "StringEquals": {
      "aws:RequestTag/eks-cluster-name": "<cluster>",
      "aws:RequestTag/kubernetes-namespace": "<namespace>",
      "aws:RequestTag/kubernetes-service-account": "<sa>"
    }
  }
}
```

Sid is sanitized to `[A-Za-z0-9]` only (IAM requirement).

## Session Tags (Workload Identity-compatible)

The credential-service passes 6 session tags identical to real EKS Workload Identity:

| Tag | Source |
|-----|--------|
| `eks-cluster-arn` | Synthetic: `arn:aws:ecp:<region>:<account>:cluster/<name>` |
| `eks-cluster-name` | Cluster name from association lookup |
| `kubernetes-namespace` | JWT `kubernetes.io` claims |
| `kubernetes-service-account` | JWT `kubernetes.io` claims |
| `kubernetes-pod-name` | JWT `kubernetes.io` claims |
| `kubernetes-pod-uid` | JWT `kubernetes.io` claims |

All 6 are set as `TransitiveTagKeys` (propagate through role chains). `SourceIdentity` is set to `<cluster>.<namespace>.<sa>` (immutable after assumption).

## IAM Permissions (CDK)

### Credential-service (ECPCredentialBroker role)

```java
.actions(List.of("sts:AssumeRole", "sts:TagSession", "sts:SetSourceIdentity"))
.resources(List.of("*"))
```

No resource constraint — scoping is via session tag conditions on the target role's trust policy.

### Mgmt-service

```java
// Read role info
.actions(List.of("iam:GetRole", "iam:ListRoleTags"))
.resources(List.of("arn:aws:iam::*:role/*"))

// Modify trust policies — only on tagged roles
.actions(List.of("iam:UpdateAssumeRolePolicy"))
.resources(List.of("arn:aws:iam::<account>:role/*"))
.conditions(Map.of("StringEquals", Map.of("iam:ResourceTag/ecp-managed", "true")))
```

## Behavioral Modes

| Role state | Behavior | API response field |
|------------|----------|-------------------|
| Tagged `ecp-managed=true` | Lambda auto-applies trust statement | `trustPolicyStatus: "APPLIED"` |
| Statement already exists (same Sid) | No-op | `trustPolicyStatus: "ALREADY_PRESENT"` |
| Not tagged / permission denied | Association created, trust not modified | `trustPolicyStatus: "MANUAL_ACTION_REQUIRED"` + `requiredTrustPolicyStatement` |

## Safety Properties

1. **Append-only on CREATE** — never removes existing trust statements
2. **Targeted removal on DELETE** — only removes the statement matching our Sid
3. **Tag-gated** — cannot modify roles the user hasn't opted in
4. **Idempotent** — duplicate creates don't duplicate statements (Sid check)
5. **Graceful degradation** — if UpdateAssumeRolePolicy fails, association still created

## Limitations

- Trust policy max size: 4,096 bytes (~10 scoped statements per role)
- Cross-account roles not supported in v1.1.0 (Lambda cannot modify foreign-account roles)
- Sid uses sanitized alphanumeric only — long cluster/namespace/sa names may collide (unlikely in practice)

## Files

| File | Purpose |
|------|---------|
| `ecp-mgmt-service/.../service/TrustPolicyService.java` | Trust policy add/remove/validate |
| `ecp-mgmt-service/.../service/DynamoDbAssociationService.java` | Integration into create/delete flows |
| `ecp-credential-service/.../service/AwsCredentialService.java` | 6 session tags + SourceIdentity |
| `infra/.../ExpressComputeControlPlaneStack.java` | IAM grants for mgmt-service |
