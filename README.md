# EKS-DX Control Plane

A serverless service that brings EKS Pod Identity (`AssumeRoleForPodIdentity`) to k3s, microk8s, and EKS-D clusters via a centralized Lambda backend with DynamoDB storage.

## How It Works

```
Pod → Pod Identity Agent → eks-dx-auth-proxy (in-cluster)
  │
  ├─ 1. TokenReview (fast-fail — K8s API validates JWT signature + audience)
  │
  └─ 2. Forward to eks-dx-lambda (via API Gateway)
       │
       ├─ 3. JWKS validation (jose4j, DynamoDB-cached JWKS)
       ├─ 4. Association lookup (DynamoDB: CLUSTER#name / namespace#sa → roleArn)
       ├─ 5. STS AssumeRole (with session tags from token claims)
       └─ 6. Return temporary AWS credentials
```

Clusters and pod identity associations are registered via the `eks-dx` CLI, which stores them in DynamoDB through the Lambda API.

## Quick Start

### Prerequisites
- Java 21+
- Maven 3.8+
- AWS credentials with `sts:AssumeRole` and DynamoDB access
- Kubernetes cluster accessible via `~/.kube/config`

### Build and Run

```bash
# Lambda (dev mode)
mvn -pl eks-dx-lambda compile quarkus:dev

# Proxy (dev mode)
mvn -pl eks-dx-auth-proxy compile quarkus:dev

# CLI (native binary)
mvn -pl eks-dx-cli package -Pnative

# Integration tests (requires DynamoDB Local on port 18000)
docker run -d -p 18000:8000 public.ecr.aws/aws-dynamodb-local/aws-dynamodb-local:latest
mvn test -Dintegration.dynamodb=true
```

## API

### Credential Exchange (called by Pod Identity Agent)
```bash
curl -X POST http://localhost:8080/clusters/my-cluster/assets \
  -H "Content-Type: application/json" \
  -d '{"token": "eyJ..."}'
```

### Cluster Management (IAM-authenticated)
```bash
# Register a cluster (CLI does this automatically)
POST /clusters  {"name", "issuer", "jwks"}
GET  /clusters
GET  /clusters/{name}
DELETE /clusters/{name}
```

### Association Management (IAM-authenticated)
```bash
POST   /clusters/{name}/pod-identity-associations
GET    /clusters/{name}/pod-identity-associations
GET    /clusters/{name}/pod-identity-associations/{id}
DELETE /clusters/{name}/pod-identity-associations/{id}
```

### Health & Metrics
```bash
curl http://localhost:8080/health/live
curl http://localhost:8080/health/ready
curl http://localhost:8080/metrics
```

## Configuration

### eks-dx-lambda (application.properties)

| Property | Default | Description |
|----------|---------|-------------|
| `eks-dx.clusters-table` | `eks-dx-clusters` | DynamoDB table for cluster registrations |
| `eks-dx.associations-table` | `eks-dx-associations` | DynamoDB table for pod identity associations |
| `aws.sts.session-duration` | `PT1H` | STS session duration |

### eks-dx-auth-proxy (application.properties)

| Property | Default | Description |
|----------|---------|-------------|
| `eks-dx.endpoint` | `https://eks-dx.plasticity.cloud` | EKS-DX Lambda API Gateway endpoint |

### Environment Variables

| Variable | Description |
|----------|-------------|
| `EKS_DX_ENDPOINT` | Lambda API Gateway URL (for eks-dx-auth-proxy) |
| `EKS_DX_CLUSTERS_TABLE` | DynamoDB clusters table name override |
| `EKS_DX_ASSOCIATIONS_TABLE` | DynamoDB associations table name override |
| `AWS_REGION` | AWS region (default: `us-east-1`) |

## CI/CD Integration

```bash
export AWS_CONTAINER_CREDENTIALS_FULL_URI=http://eks-dx-auth-proxy:8080/
export AWS_CONTAINER_AUTHORIZATION_TOKEN="Bearer $(cat /var/run/secrets/kubernetes.io/serviceaccount/token)"

# AWS SDK now uses the proxy automatically
aws s3 ls
```

## Module Structure

```
├── eks-dx-lambda/           # Credential exchange + cluster/association management (Lambda)
│   └── service/
│       ├── JwksTokenValidationService  # JWT validation via DynamoDB-cached JWKS
│       ├── DynamoDbClusterService      # Cluster CRUD (DynamoDB)
│       ├── DynamoDbAssociationService  # Association CRUD (DynamoDB)
│       └── AwsCredentialService        # STS AssumeRole
├── eks-dx-auth-proxy/          # In-cluster proxy (TokenReview + Lambda forwarding)
│   └── service/
│       ├── TokenValidationService      # K8s TokenReview (fast-fail)
│       └── LambdaForwardingService     # Forward to Lambda via API Gateway
├── eks-dx-cli/              # Native CLI for cluster + association management
├── eks-dx-pod-identity-webhook/ # K8s admission webhook (injects env vars + token volume)
├── infra/                   # CDK infrastructure
└── sam.yaml                 # SAM template (Lambda + DynamoDB + API Gateway)
```

## Deployment

- **SAM**: `sam deploy --guided`
- **CDK**: `cd infra && cdk deploy`

Both deploy: Lambda (Java 21, SnapStart), two DynamoDB tables (PAY_PER_REQUEST), API Gateway (IAM auth on management endpoints), and CloudWatch alarms.

## Security Notes

- In-cluster proxy validates tokens via Kubernetes TokenReview (fast-fail)
- Lambda validates JWT signatures independently using DynamoDB-cached JWKS
- Management endpoints require IAM (SigV4) authentication
- Requires `sts:AssumeRole` and DynamoDB table access
- Deploy the proxy in trusted networks only

## License

MIT
