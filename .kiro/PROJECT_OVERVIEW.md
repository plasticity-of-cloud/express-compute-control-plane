# AWS EKS Auth Service Proxy

## Project Overview

A Quarkus-based service that replicates the AWS EKS Auth Service (`AssumeRoleForPodIdentity`) for local development and CI/CD environments. It mirrors the real EKS auth flow end-to-end, including AWS EKS API lookups, Kubernetes TokenReview, and STS AssumeRole.

## Architecture

### Validation Flow

```
POST / (ClusterName + Token)
  │
  ├─ 1. EKS API: ListPodIdentityAssociations + DescribePodIdentityAssociation
  │       └─ fallback: ConfigMap kube-system/pod-identity-associations
  │               └─ fallback: generated arn:aws:iam::<AWS_ACCOUNT_ID>:role/eks-pod-identity-<ns>-<sa>
  │
  ├─ 2. K8s TokenReview (validates JWT signature + audience pods.eks.amazonaws.com)
  │
  └─ 3. STS AssumeRole → temporary credentials
```

### Key Components

| Component | Purpose |
|-----------|---------|
| `EksAuthResource` | `POST /` endpoint — orchestrates the full flow |
| `PodIdentityAssociationService` | EKS API → ConfigMap → generated default |
| `TokenValidationService` | Kubernetes TokenReview (signature verified by K8s API server) |
| `AwsCredentialService` | STS AssumeRole with session tags |
| `EksClientProducer` | CDI producer for `EksClient` |
| `StsClientProducer` | CDI producer for `StsClient` |

### Technology Stack

- **Java 21** / **Quarkus 3.x**
- **AWS SDK v2**: `eks`, `sts`, `auth`
- **Fabric8 Kubernetes Client**: TokenReview + ConfigMap
- **Micrometer/Prometheus**: metrics
- **system-stubs-jupiter**: env var manipulation in tests

## Building and Running

```bash
# Development mode
mvn -pl eks-auth-proxy compile quarkus:dev

# JVM build
mvn -pl eks-auth-proxy package
java -jar eks-auth-proxy/target/quarkus-app/quarkus-run.jar

# Docker
docker run -p 8080:8080 \
  -e AWS_ACCESS_KEY_ID=... \
  -e AWS_SECRET_ACCESS_KEY=... \
  -e AWS_REGION=us-east-1 \
  aws-eks-auth-service-proxy:latest
```

## Configuration

### Environment Variables

| Variable | Description |
|----------|-------------|
| `AWS_ACCOUNT_ID` | Used for fallback role ARN generation only |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | AWS credentials for EKS + STS calls |
| `AWS_REGION` | AWS region (default: `us-east-1`) |

### application.properties

| Property | Default | Description |
|----------|---------|-------------|
| `eks.pod-identity.configmap.name` | `pod-identity-associations` | Fallback ConfigMap name |
| `eks.pod-identity.configmap.namespace` | `kube-system` | Fallback ConfigMap namespace |
| `aws.sts.session-duration` | `PT1H` | STS session duration |

### Pod Identity Association Fallback (ConfigMap)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: pod-identity-associations
  namespace: kube-system
data:
  "my-cluster:default:my-sa": "arn:aws:iam::123456789012:role/my-role"
  "my-cluster:ci-cd:*": "arn:aws:iam::123456789012:role/ci-cd-role"
```

## Testing

### Unit tests (no AWS/K8s required)
```bash
mvn -pl eks-auth-proxy test
```

### AWS-provisioned integration test

Provisions real IAM role + ECR policy + pod identity association on a real EKS cluster, uses fabric8 mock K8s server for TokenReview, calls real STS. Cleans up after itself.

Requires: EKS cluster with `eks-pod-identity-agent` add-on + AWS credentials with `iam:*`, `eks:*`, `sts:AssumeRole`.

```bash
mvn -pl eks-auth-proxy test \
  -Dintegration.aws=true \
  -Dintegration.cluster=my-cluster \
  -Daws.region=ap-south-1
```

### Full flow with real token
```bash
kubectl create token <sa> -n <ns> --audience pods.eks.amazonaws.com --duration 3600s

mvn -pl eks-auth-proxy test \
  -Dintegration.full=true \
  -Dintegration.cluster=my-cluster \
  -Dintegration.token=eyJ...
```

## CI/CD Integration

```bash
export AWS_CONTAINER_CREDENTIALS_FULL_URI=http://eks-auth-proxy:8080/
export AWS_CONTAINER_AUTHORIZATION_TOKEN="Bearer $(cat /var/run/secrets/kubernetes.io/serviceaccount/token)"

# AWS SDK now uses the proxy automatically
aws s3 ls
```

## Security Notes

- Token signature verification is delegated to the Kubernetes API server via TokenReview
- Requires `eks:ListPodIdentityAssociations`, `eks:DescribePodIdentityAssociation`, `sts:AssumeRole`
- Deploy in trusted networks only
