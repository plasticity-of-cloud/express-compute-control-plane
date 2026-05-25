# SSM Parameter Contract: eks-dx-infra → eks-dx-control-plane

Terraform (in `eks-dx-infra`) writes these SSM parameters per region.
CDK (in `eks-dx-control-plane`) reads them at deploy time via `StringParameter.valueForStringParameter()`.

**Deploy order**: Terraform first → CDK second.

## Design Principle

All parameters are **region-scoped by nature** — SSM Parameter Store is a regional service.
The same Terraform module deploys to each region, producing identical parameter paths with region-specific values.

```
us-east-1:  /eks-dx/network/vpc-id → vpc-0aaa111
eu-west-1:  /eks-dx/network/vpc-id → vpc-0bbb222
```

## Required Parameters

### Network (Terraform module: network)

| SSM Path | Type | Description | Example |
|----------|------|-------------|---------|
| `/eks-dx/network/vpc-id` | `String` | VPC for eks-dx workloads | `vpc-0abc123` |
| `/eks-dx/network/public-subnet-ids` | `StringList` | Public subnets (NAT/ALB) | `subnet-aaa,subnet-bbb` |
| `/eks-dx/network/private-subnet-ids` | `StringList` | Private subnets (tenant nodes) | `subnet-ccc,subnet-ddd` |
| `/eks-dx/network/security-group-id` | `String` | SG for tenant k3s nodes | `sg-0abc123` |

### Tenant Compute (Terraform module: tenant-compute)

| SSM Path | Type | Description | Example |
|----------|------|-------------|---------|
| `/eks-dx/tenant/launch-template-id` | `String` | EC2 launch template (references region-local AMI, user-data, instance profile) | `lt-0abc123` |
| `/eks-dx/tenant/ami-id` | `String` | Region-specific AMI for k3s nodes (copied per region) | `ami-0abc123` |

## Terraform Implementation

```hcl
# --- network module ---

resource "aws_ssm_parameter" "vpc_id" {
  name  = "/eks-dx/network/vpc-id"
  type  = "String"
  value = aws_vpc.eks_dx.id
}

resource "aws_ssm_parameter" "public_subnet_ids" {
  name  = "/eks-dx/network/public-subnet-ids"
  type  = "StringList"
  value = join(",", aws_subnet.public[*].id)
}

resource "aws_ssm_parameter" "private_subnet_ids" {
  name  = "/eks-dx/network/private-subnet-ids"
  type  = "StringList"
  value = join(",", aws_subnet.private[*].id)
}

resource "aws_ssm_parameter" "security_group_id" {
  name  = "/eks-dx/network/security-group-id"
  type  = "String"
  value = aws_security_group.tenant_nodes.id
}

# --- tenant-compute module ---

resource "aws_ssm_parameter" "launch_template_id" {
  name  = "/eks-dx/tenant/launch-template-id"
  type  = "String"
  value = aws_launch_template.eks_dx_tenant.id
}

resource "aws_ssm_parameter" "ami_id" {
  name  = "/eks-dx/tenant/ami-id"
  type  = "String"
  value = aws_ami_copy.eks_dx_k3s.id  # region-specific copy
}
```

## CDK Consumer

```java
// Network
String vpcId = StringParameter.valueForStringParameter(this, "/eks-dx/network/vpc-id");
String publicSubnets = StringParameter.valueForStringParameter(this, "/eks-dx/network/public-subnet-ids");
String privateSubnets = StringParameter.valueForStringParameter(this, "/eks-dx/network/private-subnet-ids");
String securityGroupId = StringParameter.valueForStringParameter(this, "/eks-dx/network/security-group-id");

// Tenant compute
String launchTemplateId = StringParameter.valueForStringParameter(this, "/eks-dx/tenant/launch-template-id");
String amiId = StringParameter.valueForStringParameter(this, "/eks-dx/tenant/ami-id");
```

## Multi-Region Deployment

```
┌─────────────────────────────────────────────────────────┐
│  Source Region (us-east-1)                              │
│                                                         │
│  Packer → builds AMI (ami-src-111)                     │
│  Terraform → creates VPC, subnets, SG, LT              │
│           → copies AMI to target regions                │
│           → writes SSM params                           │
└─────────────────────────────────────────────────────────┘
         │ aws_ami_copy
         ▼
┌─────────────────────────────────────────────────────────┐
│  Target Region (eu-west-1)                              │
│                                                         │
│  AMI: ami-tgt-222 (new ID, same content)               │
│  Terraform → creates VPC, subnets, SG, LT              │
│           → writes SSM params (with local IDs)          │
│                                                         │
│  CDK deploy → reads /eks-dx/* from local SSM            │
│            → configures Lambda env vars                  │
└─────────────────────────────────────────────────────────┘
```

## Naming Convention

```
/eks-dx/{component}/{resource-type}
```

- `component`: logical grouping (`network`, `tenant`, `monitoring`, etc.)
- `resource-type`: what the value represents (`vpc-id`, `subnet-ids`, `ami-id`)
- Use `-ids` (plural) for `StringList` types

## Notes

- All parameters use `String` or `StringList` type — not `SecureString` (these are infrastructure references, not secrets).
- AMI IDs are region-specific — copying an AMI to another region produces a new ID.
- Launch templates reference AMIs, so they are also region-specific.
- If a parameter doesn't exist at `cdk deploy` time, deployment fails with a clear error.
- Terraform should use `lifecycle { create_before_destroy = true }` on SSM params to avoid downtime during updates.
