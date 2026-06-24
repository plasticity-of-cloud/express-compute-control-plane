# IAM Role Setup for EKS-DX Pod Identity

This guide explains how to configure IAM roles so your Kubernetes workloads can assume them via EKS-DX Pod Identity.

## Prerequisites

- An EKS-DX control plane deployed in your account
- A registered cluster (`eks-dx register-cluster`)
- The `eks-dx` CLI installed

## Step 1: Create an IAM Role

Create a role with the permissions your workload needs:

```bash
aws iam create-role \
  --role-name my-app-role \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[]}'

aws iam attach-role-policy \
  --role-name my-app-role \
  --policy-arn arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess
```

## Step 2: Tag the Role

Add the `eks-dx-managed` tag so EKS-DX can automatically configure the trust policy:

```bash
aws iam tag-role \
  --role-name my-app-role \
  --tags Key=eks-dx-managed,Value=true
```

> **Without this tag**, EKS-DX cannot modify the trust policy. You'll need to configure it manually (see [Manual Trust Policy Setup](#manual-trust-policy-setup) below).

## Step 3: Create the Pod Identity Association

```bash
eks-dx create-association \
  --cluster-name my-cluster \
  --namespace default \
  --service-account my-app-sa \
  --role-arn arn:aws:iam::123456789012:role/my-app-role
```

If the role is tagged, EKS-DX automatically adds the correct trust policy statement:

```
✓ Association created: default/my-app-sa → arn:aws:iam::123456789012:role/my-app-role
  Association ID: assoc-a1b2c3d4e5f6
✓ Trust policy updated on role
```

## What EKS-DX Configures

The trust policy statement allows the EKS-DX credential broker to assume the role, scoped to the exact cluster, namespace, and service account:

```json
{
  "Sid": "AllowEksDXmyclusterdefaultmyappsa",
  "Effect": "Allow",
  "Principal": {
    "AWS": "arn:aws:iam::YOUR_CONTROL_PLANE_ACCOUNT:role/EksDXCredentialBroker"
  },
  "Action": ["sts:AssumeRole", "sts:TagSession"],
  "Condition": {
    "StringEquals": {
      "aws:RequestTag/eks-cluster-name": "my-cluster",
      "aws:RequestTag/kubernetes-namespace": "default",
      "aws:RequestTag/kubernetes-service-account": "my-app-sa"
    }
  }
}
```

**Security:** Only pods running in the specified cluster, namespace, and service account can assume this role. The conditions are enforced by AWS STS.

## Step 4: Deploy Your Workload

Create the service account and deploy your pod:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-app-sa
  namespace: default
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
spec:
  template:
    spec:
      serviceAccountName: my-app-sa
      containers:
      - name: app
        image: my-app:latest
```

The EKS-DX pod identity webhook automatically injects the credential environment variables. Your application uses AWS SDK credentials transparently — no code changes required.

## Cleanup

When you delete the association, EKS-DX removes the trust statement from the role:

```bash
eks-dx delete-association \
  --cluster-name my-cluster \
  --association-id assoc-a1b2c3d4e5f6
```

## Manual Trust Policy Setup

If you prefer not to tag your role (or cannot), EKS-DX returns the required trust statement in the API response. Apply it yourself:

```bash
# Get the current trust policy
aws iam get-role --role-name my-app-role --query 'Role.AssumeRolePolicyDocument' > trust.json

# Add the EKS-DX statement to the Statement array (use jq or edit manually)
# Then update:
aws iam update-assume-role-policy --role-name my-app-role --policy-document file://trust.json
```

## Using Session Tags for Fine-Grained Access

EKS-DX passes the same 6 session tags as real EKS Pod Identity. Use them in resource policies for fine-grained access:

### S3 Bucket Policy (namespace-scoped)

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "AWS": "arn:aws:iam::123456789012:role/my-app-role" },
    "Action": ["s3:GetObject", "s3:PutObject"],
    "Resource": "arn:aws:s3:::my-bucket/${aws:PrincipalTag/kubernetes-namespace}/*"
  }]
}
```

### DynamoDB (service-account-scoped)

```json
{
  "Condition": {
    "ForAllValues:StringEquals": {
      "dynamodb:LeadingKeys": ["${aws:PrincipalTag/kubernetes-service-account}"]
    }
  }
}
```

### Available Session Tags

| Tag | Example | Use Case |
|-----|---------|----------|
| `eks-cluster-name` | `prod-cluster` | Scope to cluster |
| `eks-cluster-arn` | `arn:aws:eks-dx:us-east-1:123:cluster/prod` | ARN-based policies |
| `kubernetes-namespace` | `production` | Scope to namespace |
| `kubernetes-service-account` | `my-app-sa` | Scope to exact workload |
| `kubernetes-pod-name` | `my-app-7d8f9-abc` | Audit trail |
| `kubernetes-pod-uid` | `a1b2c3d4-...` | Audit trail |

## Dual-Use Roles (EKS + EKS-DX)

If you're migrating from EKS or running a hybrid environment, the same role can work with both EKS Pod Identity and EKS-DX. Add both trust statements:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "EKSPodIdentity",
      "Effect": "Allow",
      "Principal": { "Service": "pods.eks.amazonaws.com" },
      "Action": ["sts:AssumeRole", "sts:TagSession"]
    },
    {
      "Sid": "AllowEksDXmyclusterdefaultmyappsa",
      "Effect": "Allow",
      "Principal": { "AWS": "arn:aws:iam::YOUR_ACCOUNT:role/EksDXCredentialBroker" },
      "Action": ["sts:AssumeRole", "sts:TagSession", "sts:SetSourceIdentity"],
      "Condition": {
        "StringEquals": {
          "aws:RequestTag/eks-cluster-name": "my-eksd-cluster",
          "aws:RequestTag/kubernetes-namespace": "default",
          "aws:RequestTag/kubernetes-service-account": "my-app-sa"
        }
      }
    }
  ]
}
```

The same downstream ABAC policies (S3, DynamoDB, SQS) work for both because the session tags are identical.

## FAQ

**Q: Can I use existing roles without renaming them?**  
A: Yes. EKS-DX has no role naming constraints. Any IAM role name works.

**Q: What happens if I delete the `eks-dx-managed` tag after associations exist?**  
A: Existing trust statements remain on the role (they were already applied). Future association creates/deletes for that role will require manual trust policy management.

**Q: Is there a limit to how many associations a role can have?**  
A: IAM trust policies have a 4,096 byte limit. Each scoped EKS-DX statement is ~350 bytes, allowing ~10 associations per role. For roles shared across many namespaces, use a broader condition (cluster-only).

**Q: Does EKS-DX support cross-account roles?**  
A: Not in v1.1.0. Cross-account role assumption will be supported in a future release.
