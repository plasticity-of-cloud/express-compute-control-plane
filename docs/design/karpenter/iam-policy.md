# Karpenter IAM Policy Design

## Problem

Karpenter's EC2NodeClass was stuck at `ValidationSucceeded=Unknown` (AwaitingReconciliation), causing the NodePool to report `NodeClassReady=False` and refusing to provision nodes.

Root cause: the tenant instance role was missing `ec2:DescribeLaunchTemplateVersions` and the write permissions used an `aws:RequestTag` condition that blocked `RunInstances`/`CreateFleet` from referencing existing resources (launch templates, subnets, security groups) which don't carry request tags.

## Design

The IAM policy follows the [official Karpenter CloudFormation reference](https://karpenter.sh/docs/reference/cloudformation/) split into scoped statements with tag-based tenant isolation.

### Statement Breakdown

| Sid | Actions | Resource Scope | Guard |
|-----|---------|---------------|-------|
| `KarpenterResourceDiscovery` | All Describe*, pricing, SSM, IAM read | `*` | `aws:RequestedRegion` |
| `KarpenterIAMInstanceProfile` | Instance profile CRUD | `arn:...:instance-profile/*` | `aws:ResourceTag/kubernetes.io/cluster/<cluster>: owned` + `karpenter.k8s.aws/ec2nodeclass: *` |
| `KarpenterInstanceAccessActions` | RunInstances, CreateFleet | AMI, snapshot, SG, subnet, capacity-reservation, placement-group ARNs | Region-scoped ARN (read-only refs, no tag condition needed) |
| `KarpenterLaunchTemplateAccess` | RunInstances, CreateFleet | `launch-template/*` | `aws:ResourceTag/kubernetes.io/cluster/<cluster>: owned` + `karpenter.sh/nodepool: *` |
| `KarpenterCreateWithTags` | RunInstances, CreateFleet, CreateLaunchTemplate | fleet, instance, volume, ENI, LT, spot-request | `aws:RequestTag/kubernetes.io/cluster/<cluster>: owned` + `aws:RequestTag/eks:eks-cluster-name: <cluster>` + `karpenter.sh/nodepool: *` |
| `KarpenterTagOnCreate` | CreateTags | fleet, instance, volume, ENI, LT, spot-request | `ec2:CreateAction` condition limits to creation-time tagging only |
| `KarpenterTagInstances` | CreateTags | `instance/*` | `aws:ResourceTag` cluster/nodepool + `aws:TagKeys` restricted to `[eks:eks-cluster-name, karpenter.sh/nodeclaim, Name]` |
| `KarpenterDelete` | TerminateInstances, DeleteLaunchTemplate | instance, launch-template | `aws:ResourceTag/kubernetes.io/cluster/<cluster>: owned` + `karpenter.sh/nodepool: *` |
| `KarpenterPassRole` | iam:PassRole | Tenant role ARN only | `iam:PassedToService: ec2.amazonaws.com` |
| `KarpenterSQS` | SQS receive/delete | Cluster interruption queue ARN | — |

### Key Design Decisions

1. **Split RunInstances/CreateFleet into two statements** — one for referencing existing shared resources (no tag condition possible) and one for creating new resources (tag condition enforced). This mirrors Karpenter's upstream `AllowScopedEC2InstanceAccessActions` vs `AllowScopedEC2InstanceActionsWithTags`.

2. **Resource ARN scoping instead of `*`** — write actions use region-qualified ARNs (`arn:aws:ec2:<region>:*:instance/*`) rather than `Resource: "*"`.

3. **Tag-based tenant isolation** — all mutating operations require `kubernetes.io/cluster/<cluster-name>: owned` on either the request tag (creation) or resource tag (modification/deletion). This prevents cross-tenant interference.

4. **DescribeLaunchTemplateVersions added** — Karpenter v1 validates launch templates via dry-run CreateFleet, which requires reading template versions. The upstream CloudFormation doesn't explicitly list it but it's required by the validation controller.

5. **PassRole restricted to ec2.amazonaws.com** — prevents the role from being passed to other services.

### Relationship to EC2NodeClass Tags

The EC2NodeClass `spec.tags` must include:
```yaml
tags:
  kubernetes.io/cluster/<cluster-name>: owned
  karpenter.sh/nodepool: ""   # Karpenter fills this per-instance
```

These tags propagate to launch templates and instances, satisfying the IAM conditions above.

## References

- [Karpenter CloudFormation Reference](https://karpenter.sh/docs/reference/cloudformation/)
- [Karpenter Troubleshooting — EC2NodeClass Validation](https://karpenter.sh/docs/troubleshooting/#ec2nodeclass-validation)
