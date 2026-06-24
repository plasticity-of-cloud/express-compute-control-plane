# Security Hardening — Tenant IAM Scoping

## Status: Phase 1 complete (v2.0.0), Phases 2-4 planned

## Current State

The tenant-service Lambda role has `iam:CreateRole`, `iam:PutRolePolicy`, `iam:PassRole` scoped to:
```
arn:aws:iam::*:role/eks-d-xpress-tenant-*
```

This prevents creating arbitrary roles but does not prevent:
- Creating a tenant role with overly broad permissions (privilege escalation)
- Passing the role to services other than EC2
- Creating roles in other accounts (wildcard account)

## Hardening Plan

### Phase 1 — Pin to Account (Low effort, immediate)

Replace `*` with the deployment account ID in CDK:
```java
.resources(List.of(
    String.format("arn:aws:iam::%s:role/eks-d-xpress-tenant-*", Stack.of(this).getAccount()),
    String.format("arn:aws:iam::%s:instance-profile/eks-d-xpress-tenant-*", Stack.of(this).getAccount())))
```

### Phase 2 — Permissions Boundary (Medium effort)

Create a managed policy `EksDxTenantPermissionsBoundary` that caps what any tenant role can do:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:*", "dynamodb:*", "sqs:*", "secretsmanager:GetSecretValue",
        "ec2:Describe*", "ec2:CreateTags", "ec2:CreateVolume", "ec2:AttachVolume",
        "ec2:DetachVolume", "ec2:DeleteVolume", "ec2:ModifyVolume",
        "ec2:CreateSecurityGroup", "ec2:AuthorizeSecurityGroupIngress",
        "ec2:RevokeSecurityGroupIngress", "ec2:DeleteSecurityGroup",
        "elasticloadbalancing:*",
        "ecr:GetAuthorizationToken", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer",
        "ssm:GetParameter", "sts:AssumeRole",
        "execute-api:Invoke"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Deny",
      "Action": [
        "iam:*", "organizations:*", "account:*",
        "cloudtrail:DeleteTrail", "cloudtrail:StopLogging"
      ],
      "Resource": "*"
    }
  ]
}
```

Then enforce it on `iam:CreateRole`:
```java
tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
    .actions(List.of("iam:CreateRole", "iam:TagRole"))
    .resources(List.of(String.format("arn:aws:iam::%s:role/eks-d-xpress-tenant-*", account)))
    .conditions(Map.of(
        "StringEquals", Map.of(
            "iam:PermissionsBoundary",
            String.format("arn:aws:iam::%s:policy/EksDxTenantPermissionsBoundary", account))))
    .build());
```

This ensures every tenant role is capped — even if the Lambda code is compromised, it cannot create an admin-level role.

### Phase 3 — Restrict PassRole to EC2 Only (Low effort)

Add condition to `iam:PassRole`:
```java
tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
    .actions(List.of("iam:PassRole"))
    .resources(List.of(String.format("arn:aws:iam::%s:role/eks-d-xpress-tenant-*", account)))
    .conditions(Map.of(
        "StringEquals", Map.of("iam:PassedToService", "ec2.amazonaws.com")))
    .build());
```

### Phase 4 — Audit Trail (Low effort)

Add CloudTrail data event logging for IAM actions on `eks-d-xpress-tenant-*` resources. Alert on:
- Role creation without permissions boundary
- Policy attachments exceeding boundary
- PassRole to non-EC2 services

## Implementation Order

1. **Phase 1** — next CDK deploy (no code change in Lambda)
2. **Phase 3** — same deploy (condition on existing statement)
3. **Phase 2** — requires creating the boundary policy first, then updating `TenantIamService` to attach it during role creation
4. **Phase 4** — independent, can be done anytime

## Risk Assessment

| Without Hardening | Impact |
|-------------------|--------|
| Lambda compromised → creates admin role | Full account takeover |
| Tenant role escalation → attaches AdministratorAccess | Lateral movement |
| PassRole to Lambda → creates execution role | Privilege escalation chain |

With Phase 2 complete, all three vectors are blocked by the permissions boundary.
