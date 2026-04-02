# AGENTS.md

## Directory Overview and Component Map

```
src/
├── main/
│   ├── java/com/plcloud/eksauth/
│   │   ├── model/          # Data models (Request/Response)
│   │   ├── resource/       # REST endpoints
│   │   ├── service/        # Business logic services
│   │   └── EksAuthResource.java
│   ├── resources/
│   │   └── application.properties
│   └── docker/
│       ├── Dockerfile.jvm
│       └── Dockerfile.native
└── test/
    └── java/com/plcloud/eksauth/
        └── EksAuthResourceTest.java
```

### Key Entry Points

| File | Purpose |
|------|---------|
| `EksAuthResource.java` | Main REST endpoint for AssumeRoleForPodIdentity |
| `HealthResource.java` | Health check endpoints |
| `TokenValidationService.java` | JWT token validation |
| `PodIdentityAssociationService.java` | ConfigMap-based role mapping |
| `AwsCredentialService.java` | AWS STS integration |

### Directory Organization

| Directory | Purpose |
|-----------|---------|
| `model/` | Request/Response DTOs |
| `resource/` | REST API endpoints |
| `service/` | Business logic and external integrations |
| `docker/` | Docker build configurations |

## Repo-Specific Tools and Scripts

| Script | Purpose |
|--------|---------|
| `build.sh` | Build script with native/JVM options |
| `./mvnw` | Maven wrapper |

**Build Options:**
- `./build.sh` - JVM build (default)
- `./build.sh --native` - Native build
- `./build.sh --push` - Push Docker image
- `./build.sh --tag TAG` - Custom image tag
- `./build.sh --registry REGISTRY` - Custom registry

## Patterns That Deviate from Language/Framework Defaults

| Pattern | Description |
|---------|-------------|
| Token validation without cryptographic verification | For CI/CD use case, tokens are decoded without signature verification |
| ConfigMap-based role mapping | Uses Kubernetes ConfigMap instead of EKS Pod Identity |
| Default role ARN generation | Falls back to predictable role ARNs when ConfigMap is unavailable |

## Configuration Files

| File | Purpose |
|------|---------|
| `pom.xml` | Maven dependencies and build configuration |
| `src/main/resources/application.properties` | Quarkus and application configuration |
| `ConfigMap pod-identity-associations` | Role associations (Kubernetes) |

### Key Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.http.port` | 8080 | HTTP port |
| `eks.pod-identity.configmap.name` | pod-identity-associations | ConfigMap name |
| `eks.pod-identity.configmap.namespace` | kube-system | ConfigMap namespace |
| `aws.sts.session-duration` | PT1H | Session duration |

## Custom Instructions
<!-- This section is for human and agent-maintained operational knowledge.
     Add repo-specific conventions, gotchas, and workflow rules here.
     This section is preserved exactly as-is when re-running codebase-summary. -->
