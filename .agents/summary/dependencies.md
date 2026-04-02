# Dependencies

## Build Dependencies

### Maven Dependencies

| Group | Artifact | Version | Purpose |
|-------|----------|---------|---------|
| io.quarkus | quarkus-rest-jackson | - | REST API with Jackson |
| io.quarkus | quarkus-smallrye-health | - | Health checks |
| io.quarkus | quarkus-micrometer-registry-prometheus | - | Prometheus metrics |
| io.quarkus | quarkus-smallrye-openapi | - | OpenAPI documentation |
| io.fabric8 | kubernetes-client | 6.13.4 | Kubernetes API client |
| software.amazon.awssdk | sts | 2.28.17 | AWS STS SDK |
| software.amazon.awssdk | auth | 2.28.17 | AWS authentication |
| com.auth0 | java-jwt | 4.4.0 | JWT token handling |
| io.quarkus | quarkus-junit5 | - | Testing framework |
| io.rest-assured | rest-assured | - | REST API testing |

### Quarkus Platform

| Property | Value |
|----------|-------|
| Group ID | io.quarkus.platform |
| Artifact ID | quarkus-bom |
| Version | 3.15.1 |

## External Services

### Kubernetes API

| Component | Version | Purpose |
|-----------|---------|---------|
| Kubernetes Client | 6.13.4 | ConfigMap access |

**Usage:**
- Read pod identity associations ConfigMap
- Trust certificates enabled

### AWS Services

| Service | SDK | Purpose |
|---------|-----|---------|
| STS | 2.28.17 | AssumeRole operations |
| Auth | 2.28.17 | AWS authentication |

## Build Tools

| Tool | Version | Purpose |
|------|---------|---------|
| Maven | 3.8+ | Build and dependency management |
| Java | 21 | Runtime and compilation |
| Docker | Latest | Container builds |

## Native Build Dependencies

| Component | Image | Purpose |
|-----------|-------|---------|
| GraalVM | quay.io/quarkus/ubi-quarkus-graalvmce-builder-image:jdk-21 | Native compilation |

## Configuration Dependencies

### Environment Variables

| Variable | Required | Purpose |
|----------|----------|---------|
| AWS_ACCOUNT_ID | No | Default role ARN generation |
| AWS_ACCESS_KEY_ID | Yes | AWS STS operations |
| AWS_SECRET_ACCESS_KEY | Yes | AWS STS operations |
| AWS_REGION | No | AWS region (default: us-east-1) |

### ConfigMap

| Name | Namespace | Required | Purpose |
|------|-----------|----------|---------|
| pod-identity-associations | kube-system | Yes | Role associations |

## Test Dependencies

| Group | Artifact | Purpose |
|-------|----------|---------|
| io.quarkus | quarkus-junit5 | JUnit 5 support |
| io.rest-assured | rest-assured | REST API testing |

## Dependency Management

### Dependency Exclusions

None - all dependencies use default versions from Quarkus BOM.

### Transitive Dependencies

Key transitive dependencies:
- Jackson (JSON processing)
- Netty (HTTP client)
- SmallRye (Reactive messaging)
- MicroProfile (API standards)
