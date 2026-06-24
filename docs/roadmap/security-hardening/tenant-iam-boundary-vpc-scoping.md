# Security Hardening — Tenant IAM Permissions Boundary + VPC Scoping

## Status: Planned

## Problem

### Current IAM posture (tenant-service Lambda)

The tenant Lambda can currently:

1. **Create IAM roles without a permissions boundary** — `iam:CreateRole` + `iam:PutRolePolicy` are allowed on `arn:aws:iam::*:role/eks-d-xpress-tenant-*` with no `iam:PermissionsBoundary` condition. A compromised Lambda (or a bug in `TenantIamService`) could attach `AdministratorAccess` to a tenant role and assume it — full account takeover.

2. **Pass roles to arbitrary services** — `iam:PassRole` has no `iam:PassedToService` condition, so the tenant role could be passed to Lambda, ECS, or any other service, not just EC2.

3. **Use wildcard account in IAM ARNs** — all IAM resource ARNs use `arn:aws:iam::*:role/...`. The `*` allows the Lambda to operate on roles in other accounts if cross-account trust is misconfigured.

4. **EC2 mutating actions lack VPC tagging enforcement** — `ec2:RunInstances`, `ec2:CreateSubnet`, `ec2:CreateSecurityGroup` are conditionally scoped to the shared VPC ARN, but there is no tag-based enforcement ensuring created resources are always tagged back to the `eks-d-xpress` project. An untagged resource drifts out of lifecycle management.

5. **Secrets Manager ARN uses wildcard region/account** — `arn:aws:secretsmanager:*:*:secret:eks-d-xpress/tenant/*` should be pinned to the deployment region and account.

6. **DLM lifecycle policies are `Resource: "*"`** — no scoping by tag or ARN.

---

## Hardening Plan

### Phase 1 — Pin account ID in IAM and Secrets Manager ARNs (CDK-only, no Lambda change)

Replace `*` account wildcards with `Stack.of(this).getAccount()` everywhere in `EksDXpressControlPlaneStack`:

```java
// Before
"arn:aws:iam::*:role/eks-d-xpress-tenant-*"

// After
String.format("arn:aws:iam::%s:role/eks-d-xpress-tenant-*", account)
```

Same for Secrets Manager:
```java
// Before
"arn:aws:secretsmanager:*:*:secret:eks-d-xpress/tenant/*"

// After
String.format("arn:aws:secretsmanager:%s:%s:secret:eks-d-xpress/tenant/*", region, account)
```

**Risk:** None. Restricts to deployment account only.

---

### Phase 2 — Tenant role permissions boundary (boundary-gated CreateRole)

#### 2a. Create the boundary policy in CDK

```java
ManagedPolicy tenantBoundary = ManagedPolicy.Builder.create(this, "TenantRoleBoundary")
    .managedPolicyName("eks-d-xpress-tenant-boundary")
    .description("Caps maximum permissions for any EKS-DX tenant EC2 role")
    .statements(List.of(
        // What tenant roles ARE allowed to do (on EC2 nodes running EKS-D)
        PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(List.of(
                // EBS CSI, ECR pull, EKS-D node operations
                "ec2:Describe*", "ec2:CreateTags", "ec2:CreateVolume", "ec2:AttachVolume",
                "ec2:DetachVolume", "ec2:DeleteVolume", "ec2:ModifyVolume",
                "ecr:GetAuthorizationToken", "ecr:BatchGetImage",
                "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability",
                // SSM agent — required for SSM-only access (SSM_ONLY_ACCESS roadmap)
                "ssm:UpdateInstanceInformation", "ssmmessages:*", "ec2messages:*",
                // Secrets Manager — fetch SA signing key (CONTROL_PLANE_MANAGED_OIDC_JWKS roadmap)
                "secretsmanager:GetSecretValue",
                // ELB for LoadBalancer services
                "elasticloadbalancing:*",
                // S3 for etcd backups
                "s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket",
                // SQS — Karpenter interruption queue
                "sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage",
                "sqs:GetQueueUrl", "sqs:GetQueueAttributes"
            ))
            .resources(List.of("*"))
            .build(),
        // Hard deny — IAM escalation + audit evasion always blocked
        PolicyStatement.Builder.create()
            .effect(Effect.DENY)
            .actions(List.of(
                "iam:CreateRole", "iam:PutRolePolicy", "iam:AttachRolePolicy",
                "iam:CreateUser", "iam:CreateAccessKey",
                "iam:PassRole",
                "organizations:*", "account:*",
                "cloudtrail:DeleteTrail", "cloudtrail:StopLogging",
                "sts:AssumeRole"
            ))
            .resources(List.of("*"))
            .build()
    ))
    .build();
```

The boundary caps what any tenant role can do at the instance level. Even if `TenantIamService` attaches a wildcard policy to a tenant role, the boundary blocks IAM escalation paths entirely.

#### 2b. Gate `iam:CreateRole` on boundary enforcement in the Lambda policy

```java
// Require boundary on role creation — privilege escalation prevention
tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
    .actions(List.of("iam:CreateRole", "iam:TagRole"))
    .resources(List.of(
        String.format("arn:aws:iam::%s:role/eks-d-xpress-tenant-*", account)))
    .conditions(Map.of(
        "StringEquals", Map.of(
            "iam:PermissionsBoundary",
            String.format("arn:aws:iam::%s:policy/eks-d-xpress-tenant-boundary", account))))
    .build());
```

Without `iam:PermissionsBoundary` set to exactly `eks-d-xpress-tenant-boundary`, the `CreateRole` call is denied — even to the tenant Lambda itself.

#### 2c. Update `TenantIamService` to attach the boundary on role creation

```java
// TenantIamService.createTenantRole()
iam.createRole(r -> r
    .roleName("eks-d-xpress-tenant-" + tenantId)
    .assumeRolePolicyDocument(ec2TrustPolicy)
    .permissionsBoundary(
        "arn:aws:iam::" + accountId + ":policy/eks-d-xpress-tenant-boundary")
    .tags(Tag.builder().key("project").value("eks-d-xpress").build(),
          Tag.builder().key("tenant-id").value(tenantId).build()));
```

#### 2d. Gate `iam:PutRolePolicy` + `iam:AttachRolePolicy` on boundary presence

```java
tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
    .actions(List.of("iam:PutRolePolicy", "iam:AttachRolePolicy", "iam:DetachRolePolicy",
                     "iam:DeleteRolePolicy"))
    .resources(List.of(
        String.format("arn:aws:iam::%s:role/eks-d-xpress-tenant-*", account)))
    .conditions(Map.of(
        "StringEquals", Map.of(
            "iam:PermissionsBoundary",
            String.format("arn:aws:iam::%s:policy/eks-d-xpress-tenant-boundary", account))))
    .build());
```

---

### Phase 3 — Restrict `iam:PassRole` to EC2 only

```java
tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
    .actions(List.of("iam:PassRole"))
    .resources(List.of(
        String.format("arn:aws:iam::%s:role/eks-d-xpress-tenant-*", account)))
    .conditions(Map.of(
        "StringEquals", Map.of("iam:PassedToService", "ec2.amazonaws.com")))
    .build());
```

Blocks passing tenant roles to Lambda, ECS, IRSA, or any non-EC2 service.

---

### Phase 4 — Enforce VPC tagging on EC2 resource creation

All resources created in the eks-d-xpress VPC must carry `project=eks-d-xpress` and `tenant-id=<id>` tags for lifecycle management. Add `aws:RequestTag` conditions to mutating EC2 actions:

```java
tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
    .actions(List.of(
        "ec2:CreateSubnet", "ec2:CreateSecurityGroup",
        "ec2:RunInstances", "ec2:AllocateAddress", "ec2:CreateKeyPair"))
    .resources(List.of(
        String.format("arn:aws:ec2:%s:%s:subnet/*", region, account),
        String.format("arn:aws:ec2:%s:%s:security-group/*", region, account),
        String.format("arn:aws:ec2:%s:%s:instance/*", region, account),
        String.format("arn:aws:ec2:%s:%s:elastic-ip/*", region, account),
        String.format("arn:aws:ec2:%s:%s:key-pair/*", region, account),
        // vpc/* needed so RunInstances can reference the VPC
        String.format("arn:aws:ec2:%s:%s:vpc/%s", region, account, vpcId)))
    .conditions(Map.of(
        "StringEquals", Map.of("aws:RequestTag/project", "eks-d-xpress")))
    .build());
```

`TenantEc2Service` and `TenantNetworkService` already tag resources — this makes the tag a hard requirement at the IAM level, not just a convention.

---

### Phase 5 — DLM scoped by tag

DLM `Resource: "*"` can be replaced with tag-based scoping once DLM supports resource-level permissions (currently limited). Interim: add a SCP or tag policy at the AWS Organizations level scoped to the deployment account. Track [DLM resource-level IAM support](https://docs.aws.amazon.com/dlm/latest/APIReference/Welcome.html).

---

## Attack Vectors Closed

| Vector | Current | After Phase 2 |
|--------|---------|---------------|
| Lambda compromised → `CreateRole` + `AttachRolePolicy AdministratorAccess` | ✅ Possible | ❌ Blocked — boundary condition on CreateRole |
| Tenant role escalates → removes boundary via `iam:DeleteRolePolicy` | ✅ Possible | ❌ Tenant role boundary denies all IAM actions |
| `PassRole` to Lambda → creates execution chain | ✅ Possible | ❌ Blocked in Phase 3 (EC2 only) |
| Cross-account role creation via wildcard `*` account | ✅ Possible | ❌ Blocked in Phase 1 |
| Untagged resource evades lifecycle cleanup | ✅ Possible | ❌ Blocked in Phase 4 |
| Tenant EC2 node calls `sts:AssumeRole` → pivots | ✅ Possible | ❌ Boundary denies `sts:AssumeRole` |

---

## Implementation Order

| Phase | Effort | Risk | CDK change | Lambda change |
|-------|--------|------|------------|---------------|
| 1 — Pin account/region | XS | None | Yes | No |
| 3 — PassRole → EC2 only | XS | None | Yes | No |
| 2 — Boundary policy + gate | M | Low (test in dry-run first) | Yes | Yes (`TenantIamService`) |
| 4 — VPC tag enforcement | S | Low | Yes | No (tags already set) |
| 5 — DLM scoping | L | Blocked on AWS support | TBD | No |

Phase 1 and 3 are zero-risk CDK-only changes that can go in the next deploy. Phase 2 requires `TenantIamService` to pass `permissionsBoundary` on `createRole` — deploy the boundary policy first, then update the Lambda in the same CDK deployment to avoid a window where the gate is enforced but the boundary doesn't exist yet.

---

## Affected Files

- `infra/src/main/java/ai/codriverlabs/eksdx/infra/EksDXpressControlPlaneStack.java` — all phases
- `eks-dx-tenant-service/.../service/TenantIamService.java` — Phase 2c (`permissionsBoundary` on `createRole`)

## Related Documents

- `TENANT_IAM_SCOPING.md` — earlier scoping work (resource ARN narrowing)
- `SSM_ONLY_ACCESS.md` — removes SSH, reduces EC2 ingress surface
- `CONTROL_PLANE_MANAGED_OIDC_JWKS.md` — SA key custody in Secrets Manager
