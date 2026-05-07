# AGENTS.md

## Directory Overview and Component Map

This is a **multi-module Quarkus + CDK project** providing EKS Pod Identity authentication for k3s, microk8s, and EKS-D clusters via a serverless Lambda backend.

```
├── eks-dx-lambda/           # 🔑 Core credential exchange service
│   └── src/main/java/cloud/plasticity/eksdx/lambda/
│       ├── auth/            # WebhookAuthFilter (SA token audience check)
│       ├── model/           # TokenClaims record
│       ├── resource/        # REST endpoints (EksAuthResource, ClusterResource, AssociationResource)
│       └── service/         # DynamoDbClusterService, DynamoDbAssociationService,
│                            # JwksTokenValidationService, AwsCredentialService
├── eks-dx-cli/              # 🛠️ Native CLI for cluster + association management
│   └── src/main/java/cloud/plasticity/eksdx/cli/
│       ├── cluster/         # CreateCluster, Describe, List, Update, Delete commands
│       ├── association/     # CreateAssociation, Describe, List, Delete commands
│       ├── config/          # ConfigureCommand, EksDxConfig (~/.eks-dx/config)
│       └── util/            # EksDxApiClient (JDK HttpClient), AwsSigV4Signer
├── eks-dx-auth-proxy/          # 🔄 In-cluster proxy (TokenReview + Lambda forwarding)
│   └── src/main/java/cloud/plasticity/eksauth/
│       ├── resource/        # EksAuthAgentResource (POST /clusters/{name}/assets)
│       └── service/         # TokenValidationService (K8s TokenReview),
│                            # LambdaForwardingService (JDK HttpClient → Lambda)
├── eks-dx-pod-identity-webhook/ # ⚡ Kubernetes admission webhook
│   └── src/main/java/cloud/plasticity/webhook/
│       ├── WebhookEndpoint.java        # POST /mutate
│       ├── PodIdentityMutator.java     # Injects env vars + projected token volume
│       └── LambdaAssociationLookup.java # Queries Lambda API for associations
├── infra/                   # 🏗️ CDK infrastructure (alternative to SAM)
│   └── src/main/java/cloud/plasticity/eksdx/infra/
│       ├── InfraApp.java    # CDK app entry point
│       └── EksDxStack.java  # Lambda, DynamoDB, API Gateway, CloudWatch alarms
├── sam.yaml                 # 📋 SAM template (Lambda + DynamoDB + API Gateway)
└── docs/                    # 📚 User guides and setup scripts
```

## Key Entry Points

### Authentication Flow Entry Points
| Component | File | Purpose |
|-----------|------|---------|
| **Credential Exchange** | `eks-dx-lambda/.../EksAuthResource.java` | Main API endpoint for pod authentication |
| **Token Validation** | `eks-dx-lambda/.../JwksTokenValidationService.java` | JWT signature verification |
| **In-Cluster Proxy** | `eks-dx-auth-proxy/.../EksAuthAgentResource.java` | Fast-fail token validation + forwarding |
| **Webhook Controller** | `eks-dx-pod-identity-webhook/.../WebhookEndpoint.java` | Pod mutation for identity injection |

### Management API Entry Points
| Component | File | Purpose |
|-----------|------|---------|
| **Cluster Management** | `eks-dx-lambda/.../ClusterResource.java` | CRUD operations for cluster registration |
| **Association Management** | `eks-dx-lambda/.../AssociationResource.java` | Pod identity association management |
| **CLI Commands** | `eks-dx-cli/.../EksDxCommand.java` | Command-line interface entry point |

### Infrastructure Entry Points
| Component | File | Purpose |
|-----------|------|---------|
| **CDK Stack** | `infra/.../EksDxStack.java` | Complete AWS infrastructure definition |
| **SAM Template** | `sam.yaml` | Alternative serverless deployment |

## Authentication Flow

```
Pod → EKS Pod Identity Agent → eks-dx-auth-proxy (in-cluster)
  │
  ├─ 1. TokenReview (fast-fail — K8s API validates JWT signature)
  │
  └─ 2. Forward to eks-dx-lambda (Lambda via API Gateway)
       │
       ├─ 3. JWKS validation (jose4j, DynamoDB-cached JWKS)
       ├─ 4. Association lookup (DynamoDB: CLUSTER#name / namespace#sa)
       ├─ 5. STS AssumeRole (with session tags from token claims)
       └─ 6. Return temporary AWS credentials
```

## Repository-Specific Patterns

### Build System
- **Multi-module Maven**: Each component builds independently
- **Native Compilation**: CLI uses GraalVM for native binaries (`-Pnative`)
- **Container Images**: Quarkus container-image extension
- **Integration Tests**: DynamoDB Local on port 18000

### Configuration Management
- **CLI Config**: `~/.eks-dx/config` (endpoint, region)
- **Environment Variables**: Component-specific env var patterns
- **Property Files**: Quarkus application.properties per module

### Testing Strategy
- **Unit Tests**: 192 total across all modules
- **Integration Tests**: 16 tests with DynamoDB Local
- **Mock Servers**: WireMock for external API testing
- **Test Coverage**: Focused on service layer and API contracts

### Security Patterns
- **JWT Validation**: jose4j library with JWKS caching
- **AWS SigV4**: Custom implementation for CLI authentication
- **Token Audience**: Strict audience validation (`pods.eks.amazonaws.com`)
- **Session Tags**: Kubernetes metadata propagated to AWS

## Development Workflow

### Local Development
```bash
# Lambda development
mvn -pl eks-dx-lambda compile quarkus:dev

# CLI development  
mvn -pl eks-dx-cli package -Pnative

# Integration testing
docker run -d -p 18000:8000 public.ecr.aws/aws-dynamodb-local/aws-dynamodb-local:latest
mvn test -Dintegration.dynamodb=true
```

### Deployment Options
- **SAM**: `sam deploy --guided` (recommended for AWS)
- **CDK**: `cd infra && cdk deploy` (infrastructure as code)
- **Container**: Docker images for proxy and webhook components

## Domain and Naming Convention

| Context | Convention | Example |
|---------|-----------|---------|
| Java packages | `cloud.plasticity.*` | `cloud.plasticity.eksdx.lambda.service` |
| Maven groupId | `cloud.plasticity` | `<groupId>cloud.plasticity</groupId>` |
| CLI config | `~/.eks-dx/config` | endpoint, region |
| Container images | `plasticity.cloud/` prefix or ECR | `plasticity.cloud/eks-dx-auth-proxy` |
| API Gateway | `eks-dx.plasticity.cloud` | Custom domain (optional) |

## Infrastructure

### SAM (`sam.yaml`)
- Lambda (Java 21, SnapStart, 512MB)
- DynamoDB tables (PAY_PER_REQUEST)
- API Gateway (IAM auth on management endpoints, open on /assets)
- CloudWatch alarms (Lambda errors, throttles, p99 duration, DynamoDB throttles)
- Optional custom domain

### CDK (`infra/`)
- Same resources as SAM, plus PITR on DynamoDB
- REST API v1 with IAM auth
- `cdk deploy` from `infra/` directory

## Custom Instructions
<!-- This section is for human and agent-maintained operational knowledge.
     Add repo-specific conventions, gotchas, and workflow rules here.
     This section is preserved exactly as-is when re-running codebase-summary. -->
