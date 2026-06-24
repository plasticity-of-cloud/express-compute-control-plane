# Karpenter EC2NodeClass Webhook — AMI Alias Resolution

## Problem

Karpenter ≥1.10 calls `eks:DescribeCluster` when `spec.amiFamily` is set to any standard
family (`AL2023`, `Bottlerocket`, etc.) and `eksControlPlane=false` is not set. EKS-D clusters
are not registered EKS clusters, so this call fails and prevents node provisioning.

The webhook rewrites `spec.amiFamily` to `Custom` to suppress this call. However, when
`amiFamily` is `Custom`, Karpenter no longer processes `amiSelectorTerms` with `alias` entries
(e.g. `alias: al2023@v1.35`) — alias resolution is only performed for known families.

## Solution

The `Ec2NodeClassWebhookResource` resolves AMI aliases to concrete AMI IDs **before** rewriting
`amiFamily` to `Custom`, using the `AmiAliasResolverService`.

### Alias Format

```
al2023@v1.35       → EKS-optimized AL2023 for Kubernetes 1.35 (pinned)
al2023@latest      → latest EKS-optimized AL2023 (not recommended for production)
bottlerocket@v1.35 → Bottlerocket for Kubernetes 1.35
```

### SSM Resolution

AMI IDs are resolved via AWS public SSM parameters (no IAM permission required):

```
/aws/service/eks/optimized-ami/{k8s-version}/amazon-linux-2023/{arch}/standard/recommended/image_id
/aws/service/eks/optimized-ami/{k8s-version}/bottlerocket/{arch}/standard/recommended/image_id
```

Results are cached indefinitely per `(alias, arch)` in a `ConcurrentHashMap` — AMI IDs for
a pinned version are immutable.

### Multi-Architecture Handling

A single alias resolves to **two AMI IDs** — one per architecture (arm64 and x86_64):

```yaml
# Input (user-supplied)
amiSelectorTerms:
  - alias: al2023@v1.35

# After webhook mutation
amiSelectorTerms:
  - id: ami-0abc...  # arm64
  - id: ami-0def...  # x86_64
```

Karpenter calls `DescribeImages` on all IDs in `amiSelectorTerms`, reads the architecture
from each image's metadata, and selects the one compatible with the instance type it chose
to satisfy the NodePool's `kubernetes.io/arch` requirement.

This is correct per [Karpenter AMI documentation](https://karpenter.sh/docs/tasks/managing-amis/):
> "Karpenter will choose the newest **compatible** AMI when spinning up new nodes."

### NodePool Arch Optimization

The webhook also attempts to read the NodePool(s) referencing this EC2NodeClass via the
Kubernetes API (`NodePoolArchService`) to resolve only the architectures actually required.

**Ordering caveat**: Karpenter requires `EC2NodeClass` to be deployed before `NodePool`
(EC2NodeClass CRD requires `amiSelectorTerms` at creation time). When the EC2NodeClass is
first created, no NodePool exists yet — `NodePoolArchService` finds nothing and falls back
to resolving both arches. On subsequent EC2NodeClass updates, the NodePool will be present
and only the relevant arch(es) are resolved.

The fallback to both arches is always correct — Karpenter filters by architecture itself.

## Webhook Mutation Order

1. Read `spec.amiFamily` (determines userData format for bootstrap injection)
2. **Resolve AMI aliases** in `spec.amiSelectorTerms` via SSM (this step)
3. Merge cluster bootstrap fields into `spec.userData`
4. Rewrite `spec.amiFamily` → `Custom`
5. Set `spec.instanceProfile`, `spec.associatePublicIPAddress`, subnet/SG selectors, tags

## RBAC

The webhook service account requires:

```yaml
- apiGroups: ["karpenter.sh"]
  resources: ["nodepools"]
  verbs: ["get", "list"]
```

This is included in the generated `ClusterRole` via `src/main/kubernetes/kubernetes.yml`.
