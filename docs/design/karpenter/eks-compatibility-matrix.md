# EKS Pod Identity Compatibility Matrix

Tracks how closely EKS-DX replicates the real EKS Pod Identity (`AssumeRoleForPodIdentity`) behaviour, and documents intentional deviations with rationale.

## Token Validation

| Aspect | Real EKS | EKS-DX | Status | Notes |
|--------|----------|--------|--------|-------|
| Signature verification | Kubernetes API server public keys | jose4j, JWKS from DynamoDB | ✅ Equivalent | JWKS registered at cluster creation |
| Audience validation | `pods.eks.amazonaws.com` | `pods.eks.amazonaws.com` | ✅ Equivalent | Enforced by jose4j |
| Token expiry | Validated | Validated | ✅ Equivalent | Enforced by jose4j |
| Issuer validation | Kubernetes issuer | Validated via JWKS cache key | ✅ Equivalent | |

## Claims Extraction

| Claim | Real EKS | EKS-DX | Status | Notes |
|-------|----------|--------|--------|-------|
| Namespace | ✅ | ✅ | ✅ Equivalent | |
| Service account name | ✅ | ✅ | ✅ Equivalent | |
| Pod name | ✅ | ✅ | ✅ Equivalent | Extracted from TokenReview extra fields |
| Pod UID | ✅ | ✅ | ✅ Equivalent | Extracted from TokenReview extra fields |
| Service account UID | ✅ | ❌ | ⚠️ Gap | Low priority — almost no policies pin to SA UID |

## STS AssumeRole — Session Tags

| Tag | Real EKS | EKS-DX | Status | Notes |
|-----|----------|--------|--------|-------|
| `eks-cluster-name` | ✅ | ✅ | ✅ Equivalent | |
| `eks-cluster-arn` | ✅ | ❌ | 🚫 Intentionally omitted | See below |
| `kubernetes-namespace` | ✅ | ✅ | ✅ Equivalent | |
| `kubernetes-service-account` | ✅ | ✅ | ✅ Equivalent | |
| `kubernetes-pod-name` | ✅ | ✅ | ✅ Equivalent | |
| `kubernetes-pod-uid` | ✅ | ✅ | ✅ Equivalent | |
| Transitive tag keys | ✅ All K8s tags | ✅ All tags | ✅ Equivalent | Set via `transitiveTagKeys` on `AssumeRoleRequest` |

### Why `eks-cluster-arn` is intentionally omitted

Constructing a synthetic `arn:aws:eks:{region}:{accountId}:cluster/{clusterName}` ARN would introduce two risks:

1. **Privilege escalation**: If a customer runs both real EKS and EKS-DX clusters with the same name in the same account/region, the synthetic ARN would be identical to the real cluster's ARN. IAM policies scoped to that ARN would be matched by both, granting EKS-DX pods the same permissions as real EKS pods — unintended access.

2. **Namespace collision**: The `arn:aws:eks:` namespace is owned by AWS. Emitting ARNs in that namespace for non-EKS resources is impersonation of an AWS-managed resource type.

**Implication for customers**: IAM policies that use `Condition: StringEquals: aws:PrincipalTag/eks-cluster-arn` will not match EKS-DX sessions. Customers should use `eks-cluster-name` for ABAC instead.

## Response Fields

| Field | Real EKS | EKS-DX | Status | Notes |
|-------|----------|--------|--------|-------|
| `subject.namespace` | ✅ | ✅ | ✅ Equivalent | |
| `subject.serviceAccount` | ✅ | ✅ | ✅ Equivalent | |
| `audience` | `pods.eks.amazonaws.com` | `pods.eks.amazonaws.com` | ✅ Equivalent | |
| `podIdentityAssociation.id` | ✅ | ✅ | ✅ Equivalent | |
| `podIdentityAssociation.arn` | EKS association ARN | Role ARN | ⚠️ Different format | EKS-DX has no EKS association ARN; role ARN is returned instead |

## Association Storage

| Aspect | Real EKS | EKS-DX | Status | Notes |
|--------|----------|--------|--------|-------|
| Storage backend | EKS API (`CreatePodIdentityAssociation`) | DynamoDB | ✅ Acceptable | Functionally equivalent for credential exchange |
| Lookup key | `cluster:namespace:service-account` | `cluster:namespace:service-account` | ✅ Equivalent | O(1) DynamoDB GetItem |
