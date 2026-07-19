# EPIA Validation — Parity with AWS EKS

## Overview

When creating an EKS Workload Identity Association (EPIA), the real AWS EKS
`CreatePodIdentityAssociation` API performs validation on the IAM role ARN
before storing the association. Express Compute must replicate this behavior.

## AWS EKS Validation Behavior

Tested against the real EKS API (`aws eks create-pod-identity-association`):

### 1. Role existence check

```
$ aws eks create-pod-identity-association \
    --cluster-name my-cluster \
    --namespace default \
    --service-account my-sa \
    --role-arn arn:aws:iam::123456789012:role/this-role-does-not-exist

An error occurred (InvalidParameterException):
  Role provided in the request does not exist.
```

**EKS validates that the IAM role exists** before creating the association.

### 2. Trust policy check

EKS also validates that the role's trust policy allows the EKS Workload Identity
service principal (`pods.eks.amazonaws.com`) to assume it. If the trust
policy is missing, the API returns an error.

### 3. Duplicate check

Only one EPIA can exist per service account per cluster. Attempting to
create a second association for the same service account returns
`ResourceInUseException`.

## AWS API Reference

- [CreatePodIdentityAssociation](https://docs.aws.amazon.com/eks/latest/APIReference/API_CreatePodIdentityAssociation.html)
- `roleArn` (Required): "The Amazon Resource Name (ARN) of the IAM role to
  associate with the service account."
- Error: `InvalidParameterException` — returned when the role does not exist
  or the trust policy is incorrect.

## Express Compute Implementation

### Implemented

| Check | EKS behavior | Express Compute behavior | Status |
|-------|-------------|-----------------|--------|
| Role exists | `iam:GetRole` → 404 = reject | `iam:GetRole` → 404 = reject | ✅ |
| Trust policy | Verifies role trusts `pods.eks.amazonaws.com` | Verifies trust policy allows `sts:AssumeRole` | ✅ |
| Duplicate SA | `ResourceInUseException` | `409 ConflictException` | ✅ |
| Required fields | `InvalidParameterException` | `400 InvalidParameterException` | ✅ |

### Not yet implemented

| Check | EKS behavior | Notes |
|-------|-------------|-------|
| Service account exists | Not validated by EKS | K8s SA is validated at credential exchange time |

### IAM permissions required

The Lambda execution role needs `iam:GetRole` permission to validate
role existence:

```json
{
  "Effect": "Allow",
  "Action": ["iam:GetRole", "iam:ListRoleTags"],
  "Resource": "arn:aws:iam::*:role/*"
}
```

> **Note:** Since v2.1.0, the `ecp-pod-*` naming constraint is removed. Roles are scoped
> via the `ecp-managed=true` resource tag and session tag conditions on the trust policy.
> See [trust-policy-management.md](trust-policy-management.md) for details.
