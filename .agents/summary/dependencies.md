# Dependencies and External Integrations

## Core Framework Dependencies

### Quarkus Framework (3.20.3)
**Purpose**: Primary application framework for all modules
**Key Extensions**:
- `quarkus-resteasy-reactive`: REST API endpoints
- `quarkus-resteasy-reactive-jackson`: JSON serialization
- `quarkus-kubernetes-client`: Kubernetes API integration
- `quarkus-container-image-jib`: Container image building
- `quarkus-picocli`: CLI framework (eks-d-auth-cli only)
- `quarkus-smallrye-health`: Health check endpoints
- `quarkus-micrometer-registry-prometheus`: Metrics export

**Configuration**: 
- Native compilation support via GraalVM
- Dependency injection via CDI
- Configuration via application.properties

### Build System Dependencies

#### Maven (3.8+)
**Purpose**: Multi-module project build system
**Key Plugins**:
- `quarkus-maven-plugin`: Quarkus application lifecycle
- `maven-compiler-plugin`: Java compilation
- `maven-surefire-plugin`: Unit test execution
- `jib-maven-plugin`: Container image building (via Quarkus)

#### GraalVM Native Image
**Purpose**: Native compilation for CLI tool
**Configuration**:
- Container-based builds via `quarkus.native.container-build=true`
- Builder image: `quay.io/quarkus/ubi-quarkus-graalvmce-builder-image:jdk-21`
- Resource limits: 10GB memory, 4 CPU cores
- Runtime initialization fixes for Kubernetes client

## AWS SDK Dependencies

### AWS SDK for Java v2
**Purpose**: AWS service integration
**Key Modules**:
- `software.amazon.awssdk:eks`: EKS API client
- `software.amazon.awssdk:sts`: Security Token Service client
- `software.amazon.awssdk:iam`: IAM operations (test only)

**Configuration**:
```java
@ApplicationScoped
public class EksClientProducer {
    @Produces
    @ApplicationScoped
    public EksClient createEksClient() {
        return EksClient.builder()
            .region(Region.of(System.getenv("AWS_REGION")))
            .build();
    }
}
```

**Authentication Methods**:
- Environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`)
- IAM roles (when running on EC2)
- AWS credential profiles
- Default credential provider chain

### Required AWS Permissions

**For eks-auth-proxy service**:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "eks:ListPodIdentityAssociations",
        "eks:DescribePodIdentityAssociation"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": "sts:AssumeRole",
      "Resource": "arn:aws:iam::*:role/eks-pod-identity-*"
    }
  ]
}
```

**For integration tests**:
```json
{
  "Effect": "Allow",
  "Action": [
    "iam:CreateRole",
    "iam:DeleteRole",
    "iam:AttachRolePolicy",
    "iam:DetachRolePolicy",
    "eks:CreatePodIdentityAssociation",
    "eks:DeletePodIdentityAssociation"
  ],
  "Resource": "*"
}
```

## Kubernetes Integration Dependencies

### Fabric8 Kubernetes Client (6.13.4)
**Purpose**: Kubernetes API interactions
**Key Features**:
- Custom Resource Definition (CRD) support
- TokenReview API for JWT validation
- ConfigMap operations for fallback configuration
- Admission webhook support

**Configuration**:
```properties
quarkus.kubernetes-client.trust-certs=true
quarkus.kubernetes-client.connection-timeout=10s
quarkus.kubernetes-client.request-timeout=30s
```

**Native Compilation Fix**:
```properties
quarkus.native.additional-build-args=--initialize-at-run-time=io.fabric8.kubernetes.client.impl.KubernetesClientImpl
```

### Required Kubernetes Permissions

**RBAC for eks-auth-proxy**:
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: eks-auth-proxy
rules:
- apiGroups: [""]
  resources: ["configmaps"]
  verbs: ["get", "list"]
- apiGroups: ["authentication.k8s.io"]
  resources: ["tokenreviews"]
  verbs: ["create"]
- apiGroups: ["eks.amazonaws.com"]
  resources: ["podidentityassociations"]
  verbs: ["get", "list", "watch"]
```

**RBAC for eks-d-auth-cli**:
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: eks-d-auth-cli
rules:
- apiGroups: ["eks.amazonaws.com"]
  resources: ["podidentityassociations"]
  verbs: ["get", "list", "create", "update", "delete"]
```

## Container and Deployment Dependencies

### Docker/Podman
**Purpose**: Container runtime for builds and deployment
**Requirements**:
- Docker API compatible runtime
- Support for multi-stage builds
- Registry authentication for image pushing

### Jib Integration
**Purpose**: Container image building without Docker daemon
**Configuration**:
```properties
quarkus.container-image.build=true
quarkus.container-image.group=plcloud
quarkus.jib.base-jvm-image=registry.access.redhat.com/ubi8/openjdk-21:1.20
quarkus.jib.base-native-image=quay.io/quarkus/quarkus-distroless-image:2.0
```

### Base Images
- **JVM builds**: `registry.access.redhat.com/ubi8/openjdk-21:1.20`
- **Native builds**: `quay.io/quarkus/quarkus-distroless-image:2.0`
- **Builder image**: `quay.io/quarkus/ubi-quarkus-graalvmce-builder-image:jdk-21`

## Testing Dependencies

### Unit Testing
- **JUnit 5**: Primary testing framework
- **Mockito**: Mocking framework
- **AssertJ**: Fluent assertions
- **System Stubs**: Environment variable manipulation

### Integration Testing
- **WireMock**: HTTP service mocking
- **Testcontainers**: Container-based testing (if needed)
- **Fabric8 Kubernetes Mock Server**: Kubernetes API mocking

**Test Configuration**:
```java
@QuarkusTest
class ServiceTest {
    @InjectMock
    KubernetesClient kubernetesClient;
    
    @InjectMock
    EksClient eksClient;
}
```

### Test Profiles
- **Unit tests**: No external dependencies
- **Integration tests**: Real AWS resources (conditional)
- **Full flow tests**: Real tokens and clusters (conditional)

## External Service Dependencies

### Kubernetes API Server
**Purpose**: Core platform integration
**Requirements**:
- TokenReview API support
- Custom Resource Definition support
- Admission webhook support (for webhook module)
- OIDC/JWT issuer capabilities

**Endpoints Used**:
- `POST /api/v1/tokenreviews`: Token validation
- `GET /apis/eks.amazonaws.com/v1/podidentityassociations`: CRD queries
- `GET /api/v1/namespaces/{ns}/configmaps/{name}`: ConfigMap fallback
- `GET /openid/v1/jwks`: JWT public key discovery

### AWS Services

#### AWS STS (Security Token Service)
**Purpose**: Temporary credential generation
**API Operations**:
- `AssumeRole`: Primary credential generation
- `GetCallerIdentity`: Identity verification (testing)

**Configuration**:
```properties
aws.sts.session-duration=PT1H
```

#### AWS EKS (Elastic Kubernetes Service)
**Purpose**: Pod identity association lookup (fallback to local CRDs)
**API Operations**:
- `ListPodIdentityAssociations`: Association discovery
- `DescribePodIdentityAssociation`: Association details

**Usage**: Optional - system works without EKS API access

### Certificate Management

#### cert-manager (Optional)
**Purpose**: TLS certificate management for admission webhooks
**Requirements**:
- cert-manager CRDs installed
- Issuer configuration for webhook certificates
- Automatic certificate renewal

**Configuration**:
```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: eks-pod-identity-webhook-cert
spec:
  secretName: eks-pod-identity-webhook-tls
  issuerRef:
    name: selfsigned-issuer
    kind: ClusterIssuer
  dnsNames:
  - eks-pod-identity-webhook.kube-system.svc
```

## Development Dependencies

### IDE Support
- **Language Server Protocol**: Java language server
- **Quarkus Tools**: IDE extensions for Quarkus development
- **Maven Integration**: Build system integration

### Code Quality Tools
- **Maven Checkstyle**: Code style enforcement
- **SpotBugs**: Static analysis
- **JaCoCo**: Code coverage (if configured)

### Local Development
- **Quarkus Dev Mode**: Hot reload development
- **Docker Compose**: Local service orchestration (if needed)
- **Kind/k3s/minikube**: Local Kubernetes clusters

## Deployment Environment Dependencies

### Kubernetes Cluster Requirements
- **Version**: 1.20+ (for stable CRD support)
- **RBAC**: Enabled for service account permissions
- **Admission Controllers**: MutatingAdmissionWebhook enabled
- **Service Account Token Projection**: For JWT token generation

### Network Requirements
- **Egress to AWS APIs**: For STS and EKS API calls
- **Ingress from Pods**: For authentication requests
- **Internal DNS**: For service discovery within cluster

### Storage Requirements
- **ConfigMaps**: For fallback configuration
- **Secrets**: For AWS credentials (if not using IAM roles)
- **CRD Storage**: For pod identity association resources

## Version Compatibility Matrix

| Component | Minimum Version | Tested Version | Notes |
|-----------|----------------|----------------|-------|
| Java | 21 | 21.0.2 | Required for Quarkus 3.x |
| Maven | 3.8 | 3.9.x | Multi-module support |
| Kubernetes | 1.20 | 1.28+ | CRD and admission webhook support |
| Docker | 20.10 | 24.x | For container builds |
| AWS CLI | 2.0 | 2.15+ | For ECR authentication |

## Optional Dependencies

### Monitoring Stack
- **Prometheus**: Metrics collection
- **Grafana**: Metrics visualization
- **AlertManager**: Alert routing

### Logging Stack
- **Fluentd/Fluent Bit**: Log collection
- **Elasticsearch**: Log storage
- **Kibana**: Log visualization

### Service Mesh (Optional)
- **Istio**: Service mesh integration
- **Linkerd**: Alternative service mesh
- **Consul Connect**: HashiCorp service mesh

These optional dependencies can enhance observability and security but are not required for core functionality.
