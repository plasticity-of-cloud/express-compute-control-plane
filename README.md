# AWS EKS Auth Service Proxy

A Quarkus-based service that implements an AWS EKS Auth Service proxy for CI/CD environments, enabling applications to obtain temporary AWS credentials using Kubernetes service account tokens without requiring actual EKS infrastructure.

## Quick Start

### Prerequisites
- Java 21+
- Maven 3.8+
- Docker (for containerized builds)
- AWS credentials with STS permissions

### Build and Run

#### Development Mode
```bash
./mvnw compile quarkus:dev
```

#### Native Build
```bash
./build.sh --native
```

#### Docker Run
```bash
docker run -p 8080:8080 \
  -e AWS_ACCOUNT_ID=123456789012 \
  -e AWS_ACCESS_KEY_ID=your-key \
  -e AWS_SECRET_ACCESS_KEY=your-secret \
  aws-eks-auth-service-proxy:latest
```

## API Usage

### AssumeRoleForPodIdentity
```bash
curl -X POST http://localhost:8080/ \
  -H "Content-Type: application/json" \
  -d '{
    "ClusterName": "my-cluster",
    "Token": "eyJhbGciOiJSUzI1NiJ9..."
  }'
```

### Health Checks
```bash
curl http://localhost:8080/health/live   # Liveness
curl http://localhost:8080/health/ready  # Readiness
curl http://localhost:8080/metrics       # Prometheus metrics
```

## Configuration

### Pod Identity Associations
Create a ConfigMap to define service account to IAM role mappings:

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
- `AWS_ACCOUNT_ID`: AWS account ID for default role ARN generation
- `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`: AWS credentials for STS operations
- `AWS_REGION`: AWS region (default: us-east-1)

## CI/CD Integration

### Application Setup
```bash
export AWS_CONTAINER_CREDENTIALS_FULL_URI=http://eks-auth-proxy:8080/
export AWS_CONTAINER_AUTHORIZATION_TOKEN="Bearer $(cat /var/run/secrets/kubernetes.io/serviceaccount/token)"

# Now use AWS SDK normally
aws s3 ls
```

### Kubernetes Deployment
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: eks-auth-proxy
spec:
  replicas: 1
  selector:
    matchLabels:
      app: eks-auth-proxy
  template:
    metadata:
      labels:
        app: eks-auth-proxy
    spec:
      containers:
      - name: eks-auth-proxy
        image: aws-eks-auth-service-proxy:latest
        ports:
        - containerPort: 8080
        env:
        - name: AWS_ACCOUNT_ID
          value: "123456789012"
        livenessProbe:
          httpGet:
            path: /health/live
            port: 8080
        readinessProbe:
          httpGet:
            path: /health/ready
            port: 8080
```

## Features

- **EKS Auth API Compatible**: Implements the `AssumeRoleForPodIdentity` operation
- **Kubernetes Native**: Uses Fabric8 client for ConfigMap-based role associations
- **Fast Startup**: Native compilation with GraalVM for sub-second startup
- **Observability**: Health checks, Prometheus metrics, structured logging
- **CI/CD Optimized**: Designed for testing and CI/CD environments

## Architecture

1. **Token Validation**: Decodes Kubernetes JWT service account tokens
2. **Role Association**: Maps service accounts to IAM roles via ConfigMap
3. **AWS STS Integration**: Assumes roles and returns temporary credentials
4. **Response Formatting**: Returns AWS EKS Auth compatible responses

## Documentation

- [Project Overview](.kiro/PROJECT_OVERVIEW.md) - Detailed project documentation
- [Development Guide](.kiro/DEVELOPMENT.md) - Development setup and guidelines
- [CI/CD Integration](.kiro/CI_CD_INTEGRATION.md) - Integration patterns and examples

## Security Notes

- Designed for CI/CD and testing environments
- Does not perform cryptographic verification of service account tokens
- Requires AWS credentials with appropriate STS permissions
- Should be deployed in trusted networks only

## License

This project is licensed under the MIT License.
