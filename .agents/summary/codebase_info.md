# Codebase Information

## Project Overview
**EKS-DX Control Plane** - A Quarkus-based service that replicates the AWS EKS Auth Service for local development and CI/CD environments.

## Statistics
- **Total Files**: 1,562
- **Prioritized Files**: 68
- **Lines of Code**: 6,485
- **Functions**: 363
- **Classes/Structs/Enums**: 75

## Programming Languages
- **Primary**: Java 21
- **Build**: Maven
- **Infrastructure**: AWS CDK (TypeScript/Java)
- **Deployment**: SAM YAML

## Module Structure
```
eks-dx-control-plane/
├── eks-dx-lambda/           # Core Lambda service (150+ LOC)
├── eks-dx-cli/              # Native CLI tool (200+ LOC)  
├── eks-dx-auth-proxy/          # In-cluster proxy (180+ LOC)
├── eks-dx-pod-identity-webhook/ # K8s admission webhook (190+ LOC)
├── infra/                   # CDK infrastructure (190+ LOC)
└── docs/                    # User guides and scripts
```

## Key Metrics by Module

### eks-dx-lambda (Core Service)
- **Files**: 15 prioritized
- **Classes**: 25
- **Functions**: 120+
- **Purpose**: JWT validation, DynamoDB operations, STS integration

### eks-dx-cli (Management CLI)
- **Files**: 20 prioritized  
- **Classes**: 15
- **Functions**: 80+
- **Purpose**: Cluster and association management commands

### eks-dx-auth-proxy (In-Cluster Component)
- **Files**: 8 prioritized
- **Classes**: 8
- **Functions**: 25+
- **Purpose**: TokenReview validation and Lambda forwarding

### eks-dx-pod-identity-webhook (Kubernetes Integration)
- **Files**: 6 prioritized
- **Classes**: 5
- **Functions**: 15+
- **Purpose**: Pod mutation for identity injection

### infra (Infrastructure as Code)
- **Files**: 2 prioritized
- **Classes**: 2
- **Functions**: 5+
- **Purpose**: AWS resource provisioning via CDK

## Technology Dependencies
- **Framework**: Quarkus 3.x
- **Cloud**: AWS SDK v2
- **Database**: DynamoDB
- **Authentication**: jose4j (JWT/JWKS)
- **HTTP Client**: JDK HttpClient
- **Testing**: JUnit 5, Mockito, WireMock
- **Build**: Maven 3.8+, GraalVM (native compilation)

## Architecture Pattern
**Microservices with Serverless Backend**
- Event-driven authentication flow
- Stateless components with DynamoDB persistence
- Container-native deployment (Quarkus)
- Infrastructure as Code (CDK + SAM)
