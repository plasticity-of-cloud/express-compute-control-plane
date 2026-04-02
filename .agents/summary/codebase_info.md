# Codebase Information

## Project Overview

**Name:** AWS EKS Auth Service Proxy  
**Type:** Java Quarkus Service  
**Version:** 1.0.0-SNAPSHOT  
**Language:** Java 21  
**Build Tool:** Maven  
**Framework:** Quarkus 3.15.1

## Technology Stack

| Category | Technology |
|----------|-----------|
| Runtime | Quarkus 3.15.1 |
| Java Version | 21 |
| Build Tool | Maven 3.8+ |
| Kubernetes Client | Fabric8 6.13.4 |
| AWS SDK | 2.28.17 |
| JWT Library | Auth0 java-jwt 4.4.0 |
| Testing | JUnit 5, REST Assured |

## Project Structure

```
src/
├── main/
│   ├── java/com/plcloud/eksauth/
│   │   ├── model/          # Data models
│   │   ├── resource/       # REST endpoints
│   │   ├── service/        # Business logic
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

## Key Components

| Component | Type | Purpose |
|-----------|------|---------|
| EksAuthResource | Resource | Main REST endpoint for AssumeRoleForPodIdentity |
| HealthResource | Resource | Health check endpoints |
| TokenValidationService | Service | Validates Kubernetes service account tokens |
| PodIdentityAssociationService | Service | Maps service accounts to IAM roles |
| AwsCredentialService | Service | Assumes AWS roles via STS |

## Build & Deployment

- **Build:** `./mvnw clean package`
- **Native Build:** `./build.sh --native`
- **Docker:** `docker build -f src/main/docker/Dockerfile.jvm .`
- **Test:** `./mvnw test`

## Configuration

- **ConfigMap:** `pod-identity-associations` in `kube-system` namespace
- **Port:** 8080
- **Health:** `/health/live`, `/health/ready`
- **Metrics:** `/metrics`
- **OpenAPI:** `/openapi`, `/swagger-ui`
