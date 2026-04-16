# AGENTS.md

## Directory Overview and Component Map

This is a **multi-module Quarkus application** providing EKS Pod Identity authentication services for local development and CI/CD environments.

```
├── eks-auth-proxy/          # Main authentication service (REST API)
│   ├── src/main/java/com/plcloud/eksauth/
│   │   ├── model/          # Request/Response DTOs
│   │   ├── resource/       # REST endpoints (EksAuthResource, HealthResource)
│   │   └── service/        # Business logic (Token validation, AWS integration)
│   └── src/test/java/      # Unit and integration tests
├── eks-d-auth-cli/         # CLI tool for managing associations (native binary)
│   └── src/main/java/com/plcloud/eksauth/cli/
│       ├── CreateCommand.java    # Create pod identity associations
│       ├── ListCommand.java      # List associations
│       ├── DeleteCommand.java    # Delete associations
│       └── DescribeCommand.java  # Show association details
├── eks-pod-identity-webhook/    # Kubernetes admission webhook
│   └── src/main/java/com/plcloud/webhook/
│       ├── WebhookEndpoint.java     # Admission controller
│       └── PodIdentityMutator.java  # Pod mutation logic
├── eks-pod-identity-crd/    # Custom Resource Definitions
│   └── src/main/resources/crd/
│       └── pod-identity-association-crd.yaml
├── build.sh                # Multi-module build script
├── deploy.sh               # Kubernetes deployment script
└── quick-deploy.sh         # Simplified deployment
```

### Key Entry Points

| Component | File | Purpose |
|-----------|------|---------|
| **Main API** | `EksAuthResource.java` | POST / endpoint for AssumeRoleForPodIdentity |
| **Token Validation** | `TokenValidationService.java` | Kubernetes TokenReview (JWT signature + audience validation) |
| **Role Lookup** | `PodIdentityAssociationService.java` | EKS API → CRD → ConfigMap → generated default fallback |
| **AWS Integration** | `AwsCredentialService.java` | STS AssumeRole with session tags |
| **CLI Management** | `PodIdentityCommand.java` | Base class for CLI operations |
| **Webhook** | `WebhookEndpoint.java` | Kubernetes admission webhook for pod mutation |
| **Health Checks** | `HealthResource.java` | /health/live and /health/ready endpoints |

## Authentication Flow

```
AWS SDK App → EKS Pod Identity Agent → Auth Service Proxy
  │
  ├─ 1. Pod Identity Association Lookup (with fallback strategy):
  │    EKS API → CRD Resources → ConfigMap → Generated Default
  │
  ├─ 2. Kubernetes TokenReview API (validates JWT signature + audience)
  │
  ├─ 3. STS AssumeRole (with session tags from token claims)
  │
  └─ 4. Return temporary AWS credentials
```

## Build System and Tools

### Main Build Script (`build.sh`)
Multi-module Maven build with resource limits and native compilation support:

```bash
# Build all modules (JVM)
./build.sh --target all

# Build CLI as native binary (always native regardless of --native flag)
./build.sh --target cli

# Build with native compilation
./build.sh --target all --native

# Build and push to ECR
./build.sh --target all --push --ecr --region us-east-1
```

**Resource Limits**: 10GB memory, 4 CPU cores for builds (prevents system overload during GraalVM compilation)

### Module-Specific Builds
- **eks-auth-proxy**: JVM or native, uses Jib for containerization
- **eks-d-auth-cli**: Always native (GraalVM), produces standalone binary
- **eks-pod-identity-webhook**: JVM or native, admission controller
- **eks-pod-identity-crd**: CRD definitions only

### Deployment Script (`deploy.sh`)
Kubernetes deployment with dependency management:
- Installs cert-manager if missing (required for webhook TLS)
- Deploys CRDs, proxy service, CLI, and webhook
- Configures RBAC and admission webhooks
- Creates sample pod identity associations

## Configuration Patterns

### Application Properties Hierarchy
1. **Environment variables**: Runtime configuration (AWS_REGION, AWS_ACCOUNT_ID)
2. **application.properties**: Default settings per module
3. **CRD resources**: Dynamic pod identity associations
4. **ConfigMap fallback**: Static role mappings with wildcard support

### Key Configuration Files

| File | Purpose | Key Properties |
|------|---------|----------------|
| `eks-auth-proxy/src/main/resources/application.properties` | Main service config | HTTP port, JWT validation, ConfigMap settings |
| `eks-d-auth-cli/src/main/resources/application.properties` | CLI config | Native build settings, Kubernetes client config |
| `pod-identity-association-crd.yaml` | CRD schema | Custom resource definition for associations |

### ConfigMap Fallback Format
```yaml
data:
  "cluster:namespace:serviceaccount": "arn:aws:iam::account:role/role-name"
  "cluster:namespace:*": "arn:aws:iam::account:role/wildcard-role"  # Wildcard support
```

## Testing Strategy

### Test Types and Commands
```bash
# Unit tests (no external dependencies)
mvn test

# Integration tests with real AWS resources (provisions and cleans up)
mvn test -Dintegration.aws=true -Dintegration.cluster=my-cluster -Daws.region=us-east-1

# Full flow with real service account token
mvn test -Dintegration.full=true -Dintegration.cluster=my-cluster -Dintegration.token=eyJ...
```

### Test Infrastructure
- **WireMock**: HTTP service mocking for external APIs
- **Fabric8 Mock Server**: Kubernetes API mocking
- **System Stubs**: Environment variable manipulation
- **Real AWS Integration**: Provisions IAM roles and EKS associations for testing

## Technology Stack Deviations

### Native Compilation (GraalVM)
- **CLI module**: Always compiled to native binary for fast startup
- **Runtime initialization fix**: `--initialize-at-run-time=io.fabric8.kubernetes.client.impl.KubernetesClientImpl`
- **Memory limits**: 10GB for build process, 8GB for runtime

### Quarkus Extensions
- `quarkus-container-image-jib`: Container building without Docker daemon
- `quarkus-picocli`: CLI framework integration
- `quarkus-kubernetes-client`: Fabric8 Kubernetes client integration
- `quarkus-smallrye-health`: Health check endpoints

### AWS SDK Integration
- **CDI Producers**: `EksClientProducer`, `StsClientProducer` for AWS client lifecycle
- **Credential Chain**: Environment variables → IAM roles → credential profiles
- **Session Tags**: Kubernetes metadata attached to STS sessions

## Kubernetes Integration Patterns

### Custom Resource Definitions (CRDs)
- **API Group**: `eks.amazonaws.com/v1`
- **Resource**: `PodIdentityAssociation`
- **Scope**: Namespaced
- **CLI Management**: Create, list, delete, describe operations

### Admission Webhook
- **Mutation Logic**: Injects AWS credential environment variables and service account token volumes
- **TLS Requirements**: cert-manager for certificate management
- **Conditional Mutation**: Only mutates pods with associated service accounts

### RBAC Requirements
- **TokenReview**: Create permission for JWT validation
- **CRD Access**: Get, list, watch for pod identity associations
- **ConfigMap Access**: Get, list for fallback configuration

## CI/CD and Deployment Patterns

### Container Registry Support
- **ECR Integration**: Automatic repository creation and authentication
- **Multi-registry**: Support for custom registries via `--registry` flag
- **Image Tagging**: Configurable tags with `--tag` option

### Kubernetes Deployment
- **Multi-component**: Deploys proxy, CLI, webhook, and CRDs together
- **Dependency Management**: Ensures cert-manager before webhook deployment
- **Health Checks**: Liveness and readiness probes configured
- **Resource Limits**: Memory and CPU limits for production deployment

## Development Workflow Patterns

### Hot Reload Development
```bash
# Quarkus dev mode for rapid development
mvn -pl eks-auth-proxy compile quarkus:dev
```

### Local Testing
- **Mock Kubernetes**: Fabric8 mock server for local development
- **AWS LocalStack**: Optional for local AWS service simulation
- **Environment Variables**: Override AWS endpoints for local testing

## Custom Instructions
<!-- This section is for human and agent-maintained operational knowledge.
     Add repo-specific conventions, gotchas, and workflow rules here.
     This section is preserved exactly as-is when re-running codebase-summary. -->
