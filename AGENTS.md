# AGENTS.md

## Directory Overview and Component Map

```
eks-auth-proxy/src/
├── main/
│   ├── java/com/plcloud/eksauth/
│   │   ├── model/          # Request/Response DTOs
│   │   ├── resource/       # REST endpoints
│   │   └── service/        # Business logic and AWS/K8s integrations
│   ├── resources/
│   │   └── application.properties
│   └── docker/
│       ├── Dockerfile.jvm
│       └── Dockerfile.native
└── test/
    └── java/com/plcloud/eksauth/
        ├── AwsProvisionedIntegrationTest.java  # Full flow, real AWS
        ├── FullFlowIntegrationTest.java        # Full flow, real token
        ├── EksAuthIntegrationTest.java         # HTTP layer tests
        ├── EksAuthResourceTest.java            # Unit tests
        └── service/                            # Service-level unit tests
```

### Key Entry Points

| File | Purpose |
|------|---------|
| `EksAuthResource.java` | Main REST endpoint for AssumeRoleForPodIdentity |
| `HealthResource.java` | Health check endpoints |
| `TokenValidationService.java` | Kubernetes TokenReview (validates token + signature) |
| `PodIdentityAssociationService.java` | EKS API lookup → ConfigMap fallback → generated default |
| `AwsCredentialService.java` | STS AssumeRole |
| `EksClientProducer.java` | CDI producer for `EksClient` |
| `StsClientProducer.java` | CDI producer for `StsClient` |

## Validation Flow

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

## Repo-Specific Tools and Scripts

| Script | Purpose |
|--------|---------|
| `eks-auth-proxy/build.sh` | Build script with native/JVM/push options |

**Build Options:**
- `./build.sh` — JVM build (default)
- `./build.sh --native` — Native build
- `./build.sh --push` — Push Docker image
- `./build.sh --tag TAG` — Custom image tag
- `./build.sh --registry REGISTRY` — Custom registry

## Configuration Files

| File | Purpose |
|------|---------|
| `eks-auth-proxy/pom.xml` | Maven dependencies and build configuration |
| `src/main/resources/application.properties` | Quarkus and application configuration |
| `ConfigMap pod-identity-associations` | Role associations fallback (Kubernetes) |

### Key Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.http.port` | 8080 | HTTP port |
| `eks.pod-identity.configmap.name` | pod-identity-associations | Fallback ConfigMap name |
| `eks.pod-identity.configmap.namespace` | kube-system | Fallback ConfigMap namespace |
| `aws.sts.session-duration` | PT1H | Session duration |

## Testing

### Unit tests (no AWS/K8s required)
```bash
mvn -pl eks-auth-proxy test
```

### AWS-provisioned integration test
Provisions real IAM role + ECR policy + pod identity association, uses fabric8 mock K8s server for TokenReview, calls real STS. Cleans up after itself.
```bash
mvn -pl eks-auth-proxy test \
  -Dintegration.aws=true \
  -Dintegration.cluster=<cluster> \
  -Daws.region=<region>
```

### Full flow with real token
```bash
mvn -pl eks-auth-proxy test \
  -Dintegration.full=true \
  -Dintegration.cluster=<cluster> \
  -Dintegration.token=eyJ...
```

## Key Dependencies

| Dependency | Purpose |
|-----------|---------|
| `software.amazon.awssdk:eks` | Pod identity association lookup |
| `software.amazon.awssdk:sts` | AssumeRole |
| `software.amazon.awssdk:iam` | Test-only: provision IAM resources |
| `io.fabric8:kubernetes-client` | TokenReview + ConfigMap |
| `uk.org.webcompere:system-stubs-jupiter` | Env var manipulation in unit tests |

## Custom Instructions
<!-- This section is for human and agent-maintained operational knowledge.
     Add repo-specific conventions, gotchas, and workflow rules here.
     This section is preserved exactly as-is when re-running codebase-summary. -->
