# AWS EKS Auth Service Proxy

A Quarkus-based service that replicates the AWS EKS Auth Service (`AssumeRoleForPodIdentity`) for local development and CI/CD environments.

## How It Works

Each request goes through four validation steps mirroring the real EKS Auth flow:

1. **Pod Identity Association** — looks up the IAM role via `eks:ListPodIdentityAssociations` / `eks:DescribePodIdentityAssociation` (falls back to a Kubernetes ConfigMap, then a generated default)
2. **Kubernetes TokenReview** — submits the token to the K8s API server, which validates the JWT signature and audience (`pods.eks.amazonaws.com`)
3. **Signature verification** — handled by the TokenReview above (K8s API server verifies the JWT)
4. **STS AssumeRole** — assumes the mapped IAM role and returns temporary credentials

## Quick Start

### Prerequisites
- Java 21+
- Maven 3.8+
- AWS credentials (`~/.aws` or environment) with `eks:*`, `sts:AssumeRole` permissions
- Kubernetes cluster accessible via `~/.kube/config`

### Build and Run

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

## API

### AssumeRoleForPodIdentity
```bash
curl -X POST http://localhost:8080/ \
  -H "Content-Type: application/json" \
  -d '{"ClusterName": "my-cluster", "Token": "eyJ..."}'
```

### Health & Metrics
```bash
curl http://localhost:8080/health/live
curl http://localhost:8080/health/ready
curl http://localhost:8080/metrics
```

## Configuration

### Pod Identity Associations

Primary source is the AWS EKS API (requires a real cluster with `eks-pod-identity-agent` add-on).

Fallback: a Kubernetes ConfigMap:
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

### Environment Variables

| Variable | Description |
|----------|-------------|
| `AWS_ACCOUNT_ID` | Used for fallback role ARN generation only |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | AWS credentials for EKS + STS calls |
| `AWS_REGION` | AWS region (default: `us-east-1`) |

### Key application.properties

| Property | Default | Description |
|----------|---------|-------------|
| `eks.pod-identity.configmap.name` | `pod-identity-associations` | Fallback ConfigMap name |
| `eks.pod-identity.configmap.namespace` | `kube-system` | Fallback ConfigMap namespace |
| `aws.sts.session-duration` | `PT1H` | STS session duration |

## CI/CD Integration

```bash
export AWS_CONTAINER_CREDENTIALS_FULL_URI=http://eks-auth-proxy:8080/
export AWS_CONTAINER_AUTHORIZATION_TOKEN="Bearer $(cat /var/run/secrets/kubernetes.io/serviceaccount/token)"

# AWS SDK now uses the proxy automatically
aws s3 ls
```

## Testing

### Unit tests (no AWS required)
```bash
mvn -pl eks-auth-proxy test
```

### Full integration test (provisions real AWS resources)

Requires an EKS cluster with `eks-pod-identity-agent` add-on installed.

```bash
# Generate a service account token
kubectl create token <sa> -n <ns> --audience pods.eks.amazonaws.com --duration 3600s

# Run the provisioned integration test
# Creates IAM role + ECR policy + pod identity association, runs the full flow, then cleans up
mvn -pl eks-auth-proxy test \
  -Dintegration.aws=true \
  -Dintegration.cluster=my-cluster \
  -Daws.region=ap-south-1

# Run with a real pre-existing token (no AWS provisioning)
mvn -pl eks-auth-proxy test \
  -Dintegration.full=true \
  -Dintegration.cluster=my-cluster \
  -Dintegration.token=eyJ...
```

## Module Structure

```
eks-auth-proxy/src/main/java/com/plcloud/eksauth/
├── resource/
│   ├── EksAuthResource.java          # POST / endpoint
│   └── HealthResource.java
├── service/
│   ├── TokenValidationService.java   # Kubernetes TokenReview
│   ├── PodIdentityAssociationService.java  # EKS API + ConfigMap lookup
│   ├── AwsCredentialService.java     # STS AssumeRole
│   ├── EksClientProducer.java
│   └── StsClientProducer.java
└── model/
    ├── AssumeRoleForPodIdentityRequest.java
    └── AssumeRoleForPodIdentityResponse.java
```

## Security Notes

- Token signature verification is delegated to the Kubernetes API server via TokenReview
- Requires AWS credentials with `eks:ListPodIdentityAssociations`, `eks:DescribePodIdentityAssociation`, and `sts:AssumeRole`
- Deploy in trusted networks only

## License

MIT
