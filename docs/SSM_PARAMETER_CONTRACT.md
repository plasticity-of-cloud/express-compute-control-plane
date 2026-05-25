# SSM Parameter Contract: eks-dx-infra → eks-dx-control-plane

Terraform (in `eks-dx-infra`) writes these SSM parameters.
CDK (in `eks-dx-control-plane`) reads them at deploy time via `StringParameter.valueForStringParameter()`.

**Deploy order**: Terraform first → CDK second.

## Required Parameters

| SSM Path | Type | Description | Example Value |
|----------|------|-------------|---------------|
| `/eks-dx/tenant/launch-template-id` | `String` | EC2 launch template for tenant k3s nodes (pre-configured AMI, user-data, instance profile) | `lt-0abc123def456` |
| `/eks-dx/tenant/subnet-id` | `String` | Subnet where tenant EC2 instances are launched (must have internet access for k3s bootstrap) | `subnet-0abc123def456` |

## Terraform Implementation

```hcl
resource "aws_ssm_parameter" "launch_template_id" {
  name  = "/eks-dx/tenant/launch-template-id"
  type  = "String"
  value = aws_launch_template.eks_dx_tenant.id
}

resource "aws_ssm_parameter" "subnet_id" {
  name  = "/eks-dx/tenant/subnet-id"
  type  = "String"
  value = aws_subnet.eks_dx_tenant.id
}
```

## CDK Consumer (already implemented)

```java
String launchTemplateId = StringParameter.valueForStringParameter(
    this, "/eks-dx/tenant/launch-template-id");
String subnetId = StringParameter.valueForStringParameter(
    this, "/eks-dx/tenant/subnet-id");
```

These are resolved at `cdk deploy` time (not synth time), so the parameters must exist in the target account/region before deploying the CDK stack.

## Future Parameters

As the infrastructure grows, add new parameters following the naming convention:

```
/eks-dx/{component}/{resource-type}
```

Examples of potential future additions:

| SSM Path | Description |
|----------|-------------|
| `/eks-dx/network/vpc-id` | VPC ID for the eks-dx service |
| `/eks-dx/network/security-group-id` | Security group for tenant nodes |
| `/eks-dx/tenant/instance-profile-arn` | IAM instance profile for tenant EC2 |
| `/eks-dx/tenant/key-pair-name` | SSH key pair for tenant nodes |

## Notes

- All parameters use `String` type (not `SecureString`) — these are infrastructure references, not secrets.
- Parameters are in the same AWS account and region as the CDK stack.
- If a parameter doesn't exist at deploy time, `cdk deploy` will fail with a clear error.
- Terraform should use `lifecycle { create_before_destroy = true }` to avoid downtime during updates.
