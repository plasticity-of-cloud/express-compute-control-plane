# IAM Multi-Region Naming Fix

## Problem

IAM roles and instance profiles are **global** AWS resources — they share a single namespace
across all regions in an account. Deploying the control plane stack to two regions
(e.g. `us-east-1` and `eu-west-1`) with identical hardcoded names causes:

- CloudFormation stack failure on the second deployment (`EntityAlreadyExists`)
- Silent reuse of the wrong region's role if the first deployment is already running
- Incorrect trust policies on shared instance roles

## Affected Resources

| # | Location | Resource Type | Current Name | Issue |
|---|---|---|---|---|
| 1 | `ExpressComputeControlPlaneStack.java` | IAM Role | `ECPCredentialBroker` | Clashes on 2nd region deploy |
| 2 | `TenantIamService.java` | IAM Role | `express-compute-tenant-{id}-instance-role` | Clashes if same tenant-id used in two regions |
| 3 | `TenantIamService.java` | Instance Profile | `express-compute-tenant-{id}-instance-role` | Same as above |
| 4 | `TenantDlmService.java` | IAM Role | `express-compute-tenant-{id}-dlm` | Clashes if same tenant-id used in two regions |

**Not affected** (regional resources or inline-scoped names):
- Lambda function names — regional
- DynamoDB table names — regional
- SSM parameters — regional
- API Gateway names — regional
- Inline policy `express-compute-tenant-policy` — scoped within the parent role, not global

## Fix

Append the AWS region to all hardcoded IAM role and instance profile names.

### 1. CDK Stack — `ECPCredentialBroker`

```java
// Before
.roleName("ECPCredentialBroker")

// After
.roleName("ECPCredentialBroker-" + Stack.of(this).getRegion())
```

The `Stack.of(this).getRegion()` resolves to the concrete region string at synthesis time
(e.g. `ECPCredentialBroker-us-east-1`).

### 2. TenantIamService — instance role + instance profile

Region is read from `AWS_REGION` env var, which Lambda always sets.

```java
// Before
String roleName = "express-compute-tenant-" + tenantId + "-instance-role";

// After
String region = System.getenv("AWS_REGION");
String roleName = "express-compute-tenant-" + tenantId + "-" + region + "-instance-role";
```

Instance profile uses the same `roleName` variable — fixed automatically.

### 3. TenantDlmService — DLM role

```java
// Before
String roleName = "express-compute-tenant-" + tenantId + "-dlm";

// After
String region = System.getenv("AWS_REGION");
String roleName = "express-compute-tenant-" + tenantId + "-" + region + "-dlm";
```

## IAM Policy Scope Impact

The CDK stack scopes tenant IAM permissions to the prefix `express-compute-tenant-*`:

```java
.resources(List.of("arn:aws:iam::*:role/express-compute-tenant-*",
                   "arn:aws:iam::*:instance-profile/express-compute-tenant-*"))
```

The wildcard `express-compute-tenant-*` already covers the new
`express-compute-tenant-{id}-{region}-instance-role` pattern — **no IAM policy changes needed**.

## Rollout Notes

- Existing single-region deployments: renaming the CDK role triggers a role replacement
  (CloudFormation delete + create). If the role has trust policies added by
  `TenantProvisioningService`, those are regenerated on next tenant provision.
- Existing tenants: their stored `iamRoleName` in DynamoDB still points to the old name.
  They must be deprovisioned and reprovisioned, or the DynamoDB record updated manually.
- New multi-region deployments: no migration needed.
