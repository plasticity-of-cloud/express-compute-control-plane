# EKS-DX Control Plane

A serverless service that brings EKS Pod Identity to non-EKS Kubernetes clusters (EKS-D via kubeadm). Three Lambda functions handle credential exchange, cluster management, and tenant provisioning, backed by DynamoDB and deployed via CDK.

## How It Works

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

Clusters and pod identity associations are registered via the `eks-dx` CLI. Tenant infrastructure (EC2 instances running EKS-D) is provisioned on demand.

## Quick Start

### Prerequisites
- Java 25
- Maven 3.9+
- Docker (for native builds)
- AWS credentials with appropriate permissions

### Build

```bash
./build-local.sh              # JVM mode (fast)
./build-local.sh --native     # GraalVM native (tenant-service + CLI)
```

### Deploy

```bash
cd infra && cdk deploy EksDXpressControlPlaneStack
```

Requires SSM parameters written by the infrastructure stack first (see `docs/design/ssm-parameter-contract.md`).

### Integration Tests

```bash
docker run -d -p 18000:8000 public.ecr.aws/aws-dynamodb-local/aws-dynamodb-local:latest
mvn test -Dintegration.dynamodb=true
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ API Gateway (IAM auth on management, open on credential exchange)│
├──────────────┬──────────────────┬───────────────────────────────┤
│ credential-  │ mgmt-service     │ tenant-service                │
│ service      │ (JVM, 256MB)     │ (native arm64, 128MB, 15min)  │
│ (SnapStart,  │                  │                               │
│  512MB)      │ Cluster CRUD     │ Composable provisioning:      │
│              │ Association CRUD │ • TenantNetworkService         │
│ Token → STS  │ JWKS management  │ • TenantIamService            │
│ AssumeRole   │                  │ • TenantEc2Service            │
│              │                  │ • TenantDlmService            │
├──────────────┴──────────────────┴───────────────────────────────┤
│ DynamoDB: eks-dx-clusters | eks-dx-associations | eks-dx-tenants │
└─────────────────────────────────────────────────────────────────┘
```

## API

### Credential Exchange
```bash
POST /clusters/{name}/assets   # Token in body, returns AWS credentials
```

### Cluster Management (IAM SigV4)
```bash
POST   /clusters
GET    /clusters
GET    /clusters/{name}
DELETE /clusters/{name}
```

### Association Management (IAM SigV4)
```bash
POST   /clusters/{name}/pod-identity-associations
GET    /clusters/{name}/pod-identity-associations
DELETE /clusters/{name}/pod-identity-associations/{id}
```

### Tenant Provisioning (IAM SigV4)
```bash
POST   /tenants                # Create tenant (arch, ec2PricingModel, k8sVersion, assignElasticIp)
GET    /tenants/{id}           # Get state
DELETE /tenants/{id}           # Deprovision
GET    /tenants/{id}/stream    # SSE progress (Function URL)
```

## Module Structure

```
├── eks-dx-credential-service/   # Lambda: credential exchange (hot path)
├── eks-dx-mgmt-service/         # Lambda: cluster/association CRUD
├── eks-dx-tenant-service/       # Lambda: tenant provisioning + lifecycle
│   └── service/
│       ├── TenantProvisioningService   # Orchestrator
│       ├── TenantNetworkService        # Subnets, SG, route tables
│       ├── TenantIamService            # Role, policies, instance profile
│       ├── TenantEc2Service            # Instance launch, user data, EIP
│       └── TenantDlmService            # Etcd backup policy
├── eks-dx-auth-proxy/           # In-cluster proxy (TokenReview + forwarding)
├── eks-dx-pod-identity-webhook/ # Admission webhook (env + volume injection)
├── eks-dx-cli/                  # Native CLI (output: eks-dx binary)
├── eks-dx-model/                # Shared TokenClaims record
└── infra/                       # CDK stack (Java, primary deployment path)
```

## Configuration

### SSM Parameter Contract

Infrastructure writes, Lambda reads at runtime:

```
/eks-dx/launch-template/{arch}/{spot|ondemand}   # Launch template IDs
/eks-d-xpress/infra/ami/{arch}/{k8s-version}     # AMI IDs (region-specific)
/eks-dx/network/vpc-id                           # VPC
/eks-dx/network/private-subnet-ids               # Subnets
/eks-dx/network/security-group-id                # Security group
```

See `docs/design/ssm-parameter-contract.md` for full details.

### Environment Variables

| Variable | Service | Description |
|----------|---------|-------------|
| `EKS_DX_CLUSTERS_TABLE` | credential, mgmt | DynamoDB clusters table |
| `EKS_DX_ASSOCIATIONS_TABLE` | credential, mgmt | DynamoDB associations table |
| `EKS_DX_TENANTS_TABLE` | tenant | DynamoDB tenants table |
| `EKS_DX_LT_ARM64_ONDEMAND` | tenant | Launch template ID |
| `EKS_DX_LT_ARM64_SPOT` | tenant | Launch template ID |
| `EKS_DX_LT_X86_ONDEMAND` | tenant | Launch template ID |
| `EKS_DX_LT_X86_SPOT` | tenant | Launch template ID |
| `EKS_DX_VPC_ID` | tenant | VPC for tenant resources |
| `EKS_DX_ENDPOINT` | auth-proxy, tenant | API Gateway URL |

## Documentation

| Document | Purpose |
|----------|---------|
| `docs/architecture.md` | System overview and component diagrams |
| `docs/design/ssm-parameter-contract.md` | Interface between CDK and Lambda |
| `docs/design/tenant/provisioning.md` | Tenant provisioning orchestration |
| `docs/design/tenant/hibernate-resume.md` | Instance lifecycle (stop/resume) |
| `docs/design/iam/trust-policy-management.md` | Trust policy auto-management |
| `docs/user-guides/deployment.md` | Deployment guide |
| `docs/user-guides/integration-k3s.md` | k3s integration |

## License

MIT
