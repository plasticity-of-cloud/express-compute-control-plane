# IAM Permissions Reference

This document lists every IAM permission required by each Lambda function and why it's needed.

## credential-service

Handles the hot-path credential exchange: validates JWT tokens and calls STS AssumeRole.

| Permission | Resource Scope | Reason |
|-----------|---------------|--------|
| `dynamodb:GetItem` | `express-compute-clusters` table | Look up JWKS and issuer for JWT validation |
| `dynamodb:GetItem` | `express-compute-associations` table | Look up namespace/SA → roleArn mapping |
| `sts:AssumeRole` | `*` | Assume the target IAM role on behalf of the pod |
| `sts:TagSession` | `*` | Attach session tags (cluster, namespace, SA) to assumed session |
| `sts:SetSourceIdentity` | `*` | Propagate workload identity as source identity for CloudTrail |

**No write permissions.** This function is read-only + STS assume.

---

## mgmt-service

Handles cluster/association CRUD and trust policy management via API Gateway.

| Permission | Resource Scope | Reason |
|-----------|---------------|--------|
| `dynamodb:GetItem`, `PutItem`, `DeleteItem`, `UpdateItem`, `Scan`, `Query` | `express-compute-clusters` table | Cluster registration, describe, list, deregister, JWKS update |
| `dynamodb:GetItem`, `PutItem`, `DeleteItem`, `UpdateItem`, `Scan`, `Query` | `express-compute-associations` table | Association CRUD |
| `iam:GetRole` | `arn:aws:iam::*:role/*` | Check if role exists before creating association |
| `iam:ListRoleTags` | `arn:aws:iam::*:role/*` | Verify role is tagged `ecp-managed=true` before trust policy update |
| `iam:UpdateAssumeRolePolicy` | `arn:aws:iam::<account>:role/*` (condition: tag `ecp-managed=true`) | Add/remove trust policy statements for workload identitys |

---

## tenant-service

Orchestrates full cluster lifecycle: provisioning, PKI generation, network, IAM, EC2, DLM, and teardown.

### DynamoDB

| Permission | Resource Scope | Reason |
|-----------|---------------|--------|
| Full CRUD | `express-compute-tenants` table | Write tenant state, read progress, delete on teardown |
| Full CRUD | `express-compute-clusters` table | Pre-register cluster (JWKS + issuer), delete on teardown |

### KMS

| Permission | Resource Scope | Reason |
|-----------|---------------|--------|
| `kms:Sign` | `express-compute/control-plane/ca-signing-key` | Sign per-tenant CA certificate with shared HSM-backed key |

### Secrets Manager

| Permission | Resource Scope | Reason |
|-----------|---------------|--------|
| `secretsmanager:CreateSecret` | `arn:aws:secretsmanager:*:*:secret:ecp/tenant/*` | Store CA key, CA cert, SA key, SSH key |
| `secretsmanager:DeleteSecret` | Same | Cleanup on teardown or rollback |
| `secretsmanager:GetSecretValue` | Same | Read SSH key for progress stream response |

### EC2 — Read-only (Describe)

| Permission | Resource Scope | Reason |
|-----------|---------------|--------|
| `ec2:DescribeVpcs` | `*` | Read VPC CIDR for network allocation |
| `ec2:DescribeSubnets` | `*` | Find next available CIDR index |
| `ec2:DescribeRouteTables` | `*` | Look up shared route tables by name tag |
| `ec2:DescribeSecurityGroups` | `*` | Verify SG state during teardown |
| `ec2:DescribeAddresses` | `*` | Check EIP state |
| `ec2:DescribeInstances` | `*` | Poll instance state for SSE progress stream |
| `ec2:DescribeSnapshots` | `*` | Find DLM-created etcd snapshots for cleanup |

### EC2 — Network (scoped to VPC)

| Permission | Resource Scope | Reason |
|-----------|---------------|--------|
| `ec2:CreateSubnet` | VPC ARN + subnet ARN wildcard | Per-tenant public + private /24 subnets |
| `ec2:DeleteSubnet` | Same | Teardown / rollback cleanup |
| `ec2:CreateSecurityGroup` | VPC ARN + SG ARN wildcard | Per-tenant security group |
| `ec2:DeleteSecurityGroup` | Same | Teardown / rollback cleanup |
| `ec2:AuthorizeSecurityGroupIngress` | Same | Allow SSH, K8s API, kubelet, pod networking |
| `ec2:AssociateRouteTable` | VPC + subnet + route-table wildcards | Associate subnets with shared public/private route tables |

### EC2 — Instance Lifecycle

| Permission | Resource Scope | Reason |
|-----------|---------------|--------|
| `ec2:RunInstances` | `*` | Launch tenant EC2 instance via Launch Template |
| `ec2:TerminateInstances` | `*` | Teardown: terminate tenant instance |
| `ec2:StopInstances` | `*` | Hibernate cluster (stop-cluster command) |
| `ec2:StartInstances` | `*` | Resume cluster (resume-cluster command) |
| `ec2:CreateKeyPair` | `*` | Generate SSH key pair per tenant |
| `ec2:CreateTags` | `*` | Tag resources during instance launch |

### EC2 — Scoped Deletion (tag-conditioned)

| Permission | Resource Scope | Condition | Reason |
|-----------|---------------|-----------|--------|
| `ec2:ReleaseAddress` | EIP ARN wildcard | `project=express-compute` | Release tenant EIP on teardown |
| `ec2:DisassociateAddress` | Same | Same | Disassociate EIP before release |
| `ec2:DeleteKeyPair` | Key pair ARN wildcard | `project=express-compute` | Delete SSH key pair on teardown |
| `ec2:DeleteSnapshot` | Snapshot ARN wildcard | `Platform=express-compute` | Delete DLM-created etcd snapshots on teardown |
| `ec2:AllocateAddress` | `*` | — | Allocate EIP for tenant instance |
| `ec2:AssociateAddress` | `*` | — | Bind EIP to instance |

### IAM (scoped to `ecp-tenant-*`)

| Permission | Resource Scope | Reason |
|-----------|---------------|--------|
| `iam:CreateRole` | `arn:aws:iam::*:role/ecp-tenant-*` | Per-tenant instance role |
| `iam:DeleteRole` | Same | Teardown cleanup |
| `iam:PutRolePolicy` | Same | Attach inline policy (EC2 permissions) |
| `iam:DeleteRolePolicy` | Same | Teardown cleanup |
| `iam:PassRole` | Same | Pass role to EC2 RunInstances |
| `iam:TagRole` | Same | Tag role with cluster name |
| `iam:AttachRolePolicy` | Same | Attach managed policies (SSM, ECR, etc.) |
| `iam:DetachRolePolicy` | Same | Teardown cleanup |
| `iam:ListRolePolicies` | Same | Enumerate policies before deletion |
| `iam:ListAttachedRolePolicies` | Same | Enumerate managed policies before deletion |
| `iam:CreateInstanceProfile` | `arn:aws:iam::*:instance-profile/ecp-tenant-*` | Per-tenant instance profile |
| `iam:DeleteInstanceProfile` | Same | Teardown cleanup |
| `iam:AddRoleToInstanceProfile` | Same | Associate role with profile |
| `iam:RemoveRoleFromInstanceProfile` | Same | Teardown cleanup |

### DLM (Data Lifecycle Manager)

| Permission | Resource Scope | Reason |
|-----------|---------------|--------|
| `dlm:CreateLifecyclePolicy` | `*` | Create daily etcd backup policy per tenant |
| `dlm:DeleteLifecyclePolicy` | `*` | Delete policy on teardown |
| `dlm:GetLifecyclePolicies` | `*` | Find policy by tag during teardown |
| `dlm:TagResource` | `*` | Tag policy with `ecp-tenant` for lookup |

### SQS (scoped to `ecp-tenant-*`)

| Permission | Resource Scope | Reason |
|-----------|---------------|--------|
| `sqs:CreateQueue` | `arn:aws:sqs:<region>:<account>:ecp-tenant-*` | Karpenter interruption queue per cluster |
| `sqs:DeleteQueue` | Same | Teardown cleanup |
| `sqs:GetQueueUrl` | Same | Resolve queue URL for deletion |

### EventBridge (scoped to `ecp-tenant-*`)

| Permission | Resource Scope | Reason |
|-----------|---------------|--------|
| `events:PutRule` | `arn:aws:events:<region>:<account>:rule/ecp-tenant-*` | Spot interruption / rebalance rules |
| `events:PutTargets` | Same | Route events to SQS queue |
| `events:RemoveTargets` | Same | Teardown cleanup |
| `events:DeleteRule` | Same | Teardown cleanup |

### STS

| Permission | Resource Scope | Reason |
|-----------|---------------|--------|
| `sts:GetCallerIdentity` | `*` | Resolve account ID for ARN construction |

### SSM Parameter Store

| Permission | Resource Scope | Reason |
|-----------|---------------|--------|
| `ssm:GetParameter` | `/express-compute/control-plane/*`, `/express-compute/infra/ami/*` | Read API endpoint, AMI IDs at runtime |

---

## Security Principles

1. **Least privilege**: Each Lambda has only the permissions it needs — credential-service is read-only, mgmt-service can't launch EC2, tenant-service can't assume roles on behalf of pods.
2. **Resource scoping**: Mutating actions are scoped to `ecp-tenant-*` resource names or conditioned on tags.
3. **Tag-based conditions**: EIP/key-pair/snapshot deletion requires `project=express-compute` or `Platform=express-compute` tag — prevents accidental deletion of unrelated resources.
4. **KMS separation**: Only tenant-service can call `kms:Sign` on the CA key. Credential/mgmt services have no KMS access.
5. **No cross-service escalation**: credential-service can't write to DynamoDB, mgmt-service can't touch Secrets Manager, tenant-service can't assume arbitrary roles.
