# EKS-DX Control Plane

## Project Overview

A serverless service that brings EKS Pod Identity (`AssumeRoleForPodIdentity`) to k3s, microk8s, and EKS-D clusters via a centralized Lambda backend with DynamoDB storage.

## Architecture

### Credential Exchange Flow

```
Pod → Pod Identity Agent → eks-dx-auth-proxy (in-cluster)
  │
  ├─ 1. TokenReview (fast-fail — K8s API validates JWT signature + audience)
  │
  └─ 2. Forward to credential-service Lambda (via API Gateway)
       │
       ├─ 3. JWKS validation (jose4j, DynamoDB-cached JWKS)
       ├─ 4. Association lookup (DynamoDB: CLUSTER#name / namespace#sa → roleArn)
       ├─ 5. STS AssumeRole (with session tags from token claims)
       └─ 6. Return temporary AWS credentials
```

### Key Components

| Component | Purpose |
|-----------|---------|
| `EksAuthResource` (Lambda) | `POST /clusters/{name}/assets` — JWKS validation, association lookup, STS AssumeRole |
| `ClusterResource` (Lambda) | Cluster registration CRUD (DynamoDB) |
| `AssociationResource` (Lambda) | Pod identity association CRUD (DynamoDB) |
| `EksAuthAgentResource` (Proxy) | In-cluster fast-fail TokenReview + Lambda forwarding |
| `TokenValidationService` (Proxy) | Kubernetes TokenReview |
| `LambdaForwardingService` (Proxy) | JDK HttpClient → Lambda API Gateway |
| `JwksTokenValidationService` (Lambda) | jose4j JWT validation with DynamoDB-cached JWKS |
| `DynamoDbClusterService` (Lambda) | Cluster CRUD (DynamoDB) |
| `DynamoDbAssociationService` (Lambda) | Association CRUD (DynamoDB) |
| `AwsCredentialService` (Lambda) | STS AssumeRole with session tags |
| `PodIdentityMutator` (Webhook) | Injects env vars + projected token volume into pods |

### Technology Stack

- **Java 25** / **Quarkus 3.35+**
- **AWS SDK v2**: `sts`, `dynamodb`
- **jose4j**: JWT/JWKS validation
- **Fabric8 Kubernetes Client**: TokenReview
- **Micrometer/Prometheus**: metrics

## Building and Running

```bash
# Lambda services (JVM)
./build-local.sh --only credential,mgmt,tenant --skip-tests

# Tenant service native (production)
./build-local.sh --only tenant --native

# CLI
./build-local.sh --only cli --skip-tests

# Deploy (JVM mode, fast iteration)
./deploy-local.sh --skip-build --context jvmTenant=true

# Integration tests (requires DynamoDB Local on port 18000)
docker run -d -p 18000:8000 public.ecr.aws/aws-dynamodb-local/aws-dynamodb-local:latest
mvn test -Dintegration.dynamodb=true
```

## Tenant Lifecycle

```bash
./create_tenant.sh <tenant-id>   # Provision EKS-D cluster (streams progress via SSE)
./delete_tenant.sh <tenant-id>   # Deprovision and wait for completion
```

SSH key is stored in Secrets Manager at `eks-d-xpress/tenant/<id>/ssh-key` and saved locally to `~/.eks-d-xpress/tenants/<region>/<id>.pem` on successful provisioning.

## Configuration

### credential-service (Lambda)

| Property | Default | Description |
|----------|---------|-------------|
| `eks-dx.clusters-table` | `eks-dx-clusters` | DynamoDB table for cluster registrations |
| `eks-dx.associations-table` | `eks-dx-associations` | DynamoDB table for pod identity associations |
| `aws.sts.session-duration` | `PT1H` | STS session duration |

### eks-dx-auth-proxy

| Property | Default | Description |
|----------|---------|-------------|
| `eks-dx.endpoint` | `https://eks-dx.codriverlabs.ai` | EKS-DX Lambda API Gateway endpoint |

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

## Security Notes

- In-cluster proxy validates tokens via Kubernetes TokenReview (fast-fail)
- Lambda validates JWT signatures independently using DynamoDB-cached JWKS
- Management endpoints require IAM (SigV4) authentication
- Requires `sts:AssumeRole` and DynamoDB table access
- Deploy the proxy in trusted networks only
