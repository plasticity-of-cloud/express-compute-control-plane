# SSM Parameter Contract: ecp-infra → ecp-control-plane

Terraform (in `ecp-infra`) writes these SSM parameters per region.
CDK (in `ecp-control-plane`) reads them at deploy time via `StringParameter.valueForStringParameter()`.

**Deploy order**: Terraform first → CDK second.

## Design Principles

1. **Region-scoped** — SSM Parameter Store is regional. Same paths, different values per region.
2. **Hierarchical paths** — supports `get-parameters-by-path` for discovery and listing.
3. **Consistent structure** — `/{project}/{scope}/{resource-type}/{arch}/{variant}`

## Parameter Hierarchy

```
/express-compute/
├── infra/                              ← written by express-compute-infra CDK stack
│   ├── ami/
│   │   ├── arm64/
│   │   │   └── 1.35              → ami-0aaa111
│   │   └── x86_64/
│   │       └── 1.35              → ami-0bbb222
│   ├── launch-template/
│   │   ├── arm64/
│   │   │   ├── ondemand          → lt-0aaa111
│   │   │   └── spot              → lt-0bbb222
│   │   └── x86_64/
│   │       ├── ondemand          → lt-0ccc333
│   │       └── spot              → lt-0ddd444
│   └── network/
│       └── vpc-id                → vpc-0abc123
└── control-plane/                      ← written by express-compute-control-plane CDK stack
    ├── api/
    │   └── endpoint              → https://xxx.execute-api.us-east-1.amazonaws.com/prod
    └── quota/
        └── max-tenants-per-caller → 1
```

## Discovery via get-parameters-by-path

```bash
# All AMIs for arm64 (all k8s versions)
aws ssm get-parameters-by-path --path /express-compute/infra/ami/arm64

# All launch templates for arm64 (spot + ondemand)
aws ssm get-parameters-by-path --path /express-compute/infra/launch-template/arm64

# All launch templates (all arches, all types)
aws ssm get-parameters-by-path --path /express-compute/infra/launch-template --recursive

# All network params
aws ssm get-parameters-by-path --path /express-compute/infra/network
```

## Full Parameter List

### AMIs (per arch, per k8s version)

| SSM Path | Type | Description |
|----------|------|-------------|
| `/express-compute/infra/ami/arm64/{k8s-version}` | `String` | Region-specific AMI for arm64 k3s nodes |
| `/express-compute/infra/ami/x86_64/{k8s-version}` | `String` | Region-specific AMI for x86_64 k3s nodes |

### Launch Templates (per arch, per pricing model)

| SSM Path | Type | Description |
|----------|------|-------------|
| `/express-compute/infra/launch-template/arm64/ondemand` | `String` | LT: arm64 on-demand instances |
| `/express-compute/infra/launch-template/arm64/spot` | `String` | LT: arm64 spot instances |
| `/express-compute/infra/launch-template/x86_64/ondemand` | `String` | LT: x86_64 on-demand instances |
| `/express-compute/infra/launch-template/x86_64/spot` | `String` | LT: x86_64 spot instances |

### Network

| SSM Path | Type | Description |
|----------|------|-------------|
| `/express-compute/infra/network/vpc-id` | `String` | VPC for ecp workloads |
| `/express-compute/infra/network/public-subnet-ids` | `StringList` | Public subnets (NAT/ALB) |
| `/express-compute/infra/network/private-subnet-ids` | `StringList` | Private subnets (tenant nodes) |
| `/express-compute/infra/network/security-group-id` | `String` | SG for tenant k3s nodes |

## Terraform Implementation

```hcl
variable "k8s_version" {
  default = "1.35"
}

# --- AMIs (per arch, per k8s version) ---

resource "aws_ssm_parameter" "ami_arm64" {
  name  = "/express-compute/infra/ami/arm64/${var.k8s_version}"
  type  = "String"
  value = aws_ami_copy.k3s_arm64.id
}

resource "aws_ssm_parameter" "ami_x86" {
  name  = "/express-compute/infra/ami/x86_64/${var.k8s_version}"
  type  = "String"
  value = aws_ami_copy.k3s_x86.id
}

# --- Launch Templates (per arch, per pricing) ---

resource "aws_ssm_parameter" "lt_arm64_ondemand" {
  name  = "/express-compute/infra/launch-template/arm64/ondemand"
  type  = "String"
  value = aws_launch_template.arm64_ondemand.id
}

resource "aws_ssm_parameter" "lt_arm64_spot" {
  name  = "/express-compute/infra/launch-template/arm64/spot"
  type  = "String"
  value = aws_launch_template.arm64_spot.id
}

resource "aws_ssm_parameter" "lt_x86_ondemand" {
  name  = "/express-compute/infra/launch-template/x86_64/ondemand"
  type  = "String"
  value = aws_launch_template.x86_ondemand.id
}

resource "aws_ssm_parameter" "lt_x86_spot" {
  name  = "/express-compute/infra/launch-template/x86_64/spot"
  type  = "String"
  value = aws_launch_template.x86_spot.id
}

# --- Network ---

resource "aws_ssm_parameter" "vpc_id" {
  name  = "/express-compute/infra/network/vpc-id"
  type  = "String"
  value = aws_vpc.eks_dx.id
}

resource "aws_ssm_parameter" "public_subnet_ids" {
  name  = "/express-compute/infra/network/public-subnet-ids"
  type  = "StringList"
  value = join(",", aws_subnet.public[*].id)
}

resource "aws_ssm_parameter" "private_subnet_ids" {
  name  = "/express-compute/infra/network/private-subnet-ids"
  type  = "StringList"
  value = join(",", aws_subnet.private[*].id)
}

resource "aws_ssm_parameter" "security_group_id" {
  name  = "/express-compute/infra/network/security-group-id"
  type  = "String"
  value = aws_security_group.tenant_nodes.id
}
```

## CDK Consumer

```java
// Launch templates — tenant service selects at runtime based on request
String ltArm64Ondemand = StringParameter.valueForStringParameter(this, "/express-compute/infra/launch-template/arm64/ondemand");
String ltArm64Spot = StringParameter.valueForStringParameter(this, "/express-compute/infra/launch-template/arm64/spot");
String ltX86Ondemand = StringParameter.valueForStringParameter(this, "/express-compute/infra/launch-template/x86_64/ondemand");
String ltX86Spot = StringParameter.valueForStringParameter(this, "/express-compute/infra/launch-template/x86_64/spot");

// Network
String subnetIds = StringParameter.valueForStringParameter(this, "/express-compute/infra/network/private-subnet-ids");
```

Note: AMIs are not consumed by CDK directly — the launch templates already reference them.
The tenant-service Lambda can also read AMI params at runtime via SDK if it needs version selection.

## Multi-Region Deployment

```
Source Region (us-east-1)              Target Region (eu-west-1)
─────────────────────────              ─────────────────────────
Packer → AMI (ami-src-111)    ──copy──► AMI (ami-tgt-222)
Terraform:                             Terraform:
  /express-compute/infra/ami/arm64/1.35 = ami-src       /express-compute/infra/ami/arm64/1.35 = ami-tgt
  /express-compute/infra/launch-template/...            /express-compute/infra/launch-template/...
  /express-compute/infra/network/...                    /express-compute/infra/network/...
```

## Notes

- All parameters use `String` or `StringList` — not `SecureString`.
- AMI IDs are region-specific — copying produces a new ID.
- Launch templates reference AMIs, so they are also region-specific.
- The k8s version in AMI paths enables multiple versions to coexist (rolling upgrades).
- `get-parameters-by-path` with `--recursive` traverses the full subtree.
