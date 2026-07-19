# Express Compute Control Plane

A serverless service that brings EKS Workload Identity to non-EKS Kubernetes clusters (EKS-D via kubeadm). Three Lambda functions handle credential exchange, cluster management, and tenant provisioning, backed by DynamoDB and deployed via CDK.

## How It Works

```
Pod → Workload Identity Agent → ecp-auth-proxy (in-cluster)
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

Clusters and workload identitys are registered via the `ecp` CLI. Tenant infrastructure (EC2 instances running EKS-D) is provisioned on demand.

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
cd infra && cdk deploy ExpressComputeControlPlaneStack
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
│ DynamoDB: ecp-clusters | ecp-workload-identities | ecp-tenants │
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
POST   /clusters/{name}/workload-identities
GET    /clusters/{name}/workload-identities
DELETE /clusters/{name}/workload-identities/{id}
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
├── ecp-credential-service/   # Lambda: credential exchange (hot path)
├── ecp-mgmt-service/         # Lambda: cluster/association CRUD
├── ecp-tenant-service/       # Lambda: tenant provisioning + lifecycle
│   └── service/
│       ├── TenantProvisioningService   # Orchestrator
│       ├── TenantNetworkService        # Subnets, SG, route tables
│       ├── TenantIamService            # Role, policies, instance profile
│       ├── TenantEc2Service            # Instance launch, user data, EIP
│       └── TenantDlmService            # Etcd backup policy
├── ecp-auth-proxy/           # In-cluster proxy (TokenReview + forwarding)
├── ecp-workload-identity-webhook/ # Admission webhook (env + volume injection)
├── ecp-cli/                  # Native CLI (output: ecp binary)
├── ecp-model/                # Shared TokenClaims record
└── infra/                       # CDK stack (Java, primary deployment path)
```

## Configuration

### SSM Parameter Contract

Infrastructure writes, Lambda reads at runtime:

```
/express-compute/infra/launch-template/{arch}/{spot|ondemand}  # Launch template IDs
/express-compute/infra/ami/{arch}/{k8s-version}                # AMI IDs (region-specific)
/express-compute/infra/network/vpc-id                          # VPC
/express-compute/control-plane/api/endpoint                    # API Gateway URL
/express-compute/control-plane/quota/max-tenants-per-caller    # Quota (default: 1)
```

See `docs/design/ssm-parameter-contract.md` for full details.

### Environment Variables

| Variable | Service | Description |
|----------|---------|-------------|
| `ECP_CLUSTERS_TABLE` | credential, mgmt | DynamoDB clusters table |
| `ECP_ASSOCIATIONS_TABLE` | credential, mgmt | DynamoDB associations table |
| `ECP_TENANTS_TABLE` | tenant | DynamoDB tenants table |
| `ECP_LT_ARM64_ONDEMAND` | tenant | Launch template ID |
| `ECP_LT_ARM64_SPOT` | tenant | Launch template ID |
| `ECP_LT_X86_ONDEMAND` | tenant | Launch template ID |
| `ECP_LT_X86_SPOT` | tenant | Launch template ID |
| `ECP_VPC_ID` | tenant | VPC for tenant resources |
| `ECP_ENDPOINT` | auth-proxy, tenant | API Gateway URL |

## Documentation

| Document | Purpose |
|----------|---------|
| `docs/architecture.md` | System overview and component diagrams |
| `docs/design/ssm-parameter-contract.md` | Interface between CDK and Lambda |
| `docs/design/tenant/provisioning.md` | Tenant provisioning orchestration |
| `docs/design/tenant/hibernate-resume.md` | Instance lifecycle (stop/resume) |
| `docs/design/iam/trust-policy-management.md` | Trust policy auto-management |
| `docs/user-guides/deployment.md` | Deployment guide |
| `docs/user-guides/cli-reference.md` | Complete CLI command reference |
| `docs/user-guides/integration-k3s.md` | k3s integration |

## License

Express-Compute Community License — see [LICENSE.md](LICENSE.md)
