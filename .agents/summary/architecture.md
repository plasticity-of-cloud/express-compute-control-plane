# System Architecture

## Overview

The AWS EKS Auth Service Proxy is a multi-module system that replicates AWS EKS Pod Identity authentication for local development and CI/CD environments. It follows a microservices architecture with clear separation of concerns.

## High-Level Architecture

```mermaid
graph TB
    subgraph "Client Layer"
        A[AWS SDK Applications]
        B[kubectl/CLI Tools]
    end
    
    subgraph "Kubernetes Cluster"
        C[EKS Pod Identity Agent]
        D[Service Accounts]
        E[Pods with Workloads]
    end
    
    subgraph "Auth Service Proxy"
        F[REST API Endpoint]
        G[Token Validation Service]
        H[Pod Identity Association Service]
        I[AWS Credential Service]
    end
    
    subgraph "Configuration Layer"
        J[CRD Resources]
        K[ConfigMap Fallback]
        L[Generated Defaults]
    end
    
    subgraph "External Services"
        M[Kubernetes API Server]
        N[AWS STS]
        O[AWS EKS API]
    end
    
    A --> C
    C --> F
    F --> G
    G --> M
    F --> H
    H --> J
    H --> K
    H --> L
    F --> I
    I --> N
    H --> O
    
    B --> J
```

## Authentication Flow

```mermaid
sequenceDiagram
    participant App as AWS SDK App
    participant Agent as Pod Identity Agent
    participant Proxy as Auth Service Proxy
    participant K8s as Kubernetes API
    participant STS as AWS STS
    
    App->>Agent: AWS API Call
    Agent->>Proxy: POST / (ClusterName + Token)
    
    Note over Proxy: 1. Pod Identity Association Lookup
    Proxy->>K8s: List/Describe CRDs
    alt CRD Found
        K8s-->>Proxy: Role ARN from CRD
    else ConfigMap Fallback
        Proxy->>K8s: Get ConfigMap
        K8s-->>Proxy: Role ARN from ConfigMap
    else Generated Default
        Proxy->>Proxy: Generate default ARN
    end
    
    Note over Proxy: 2. Token Validation
    Proxy->>K8s: TokenReview API
    K8s-->>Proxy: Token validation result
    
    Note over Proxy: 3. AWS Role Assumption
    Proxy->>STS: AssumeRole
    STS-->>Proxy: Temporary credentials
    
    Proxy-->>Agent: Credentials response
    Agent-->>App: AWS credentials
```

## Module Architecture

### eks-auth-proxy (Core Service)

```mermaid
classDiagram
    class EksAuthResource {
        +assumeRoleForPodIdentity()
    }
    
    class TokenValidationService {
        +validateToken()
        +getSessionTags()
    }
    
    class PodIdentityAssociationService {
        +getRoleArnForServiceAccount()
        +getRoleArnFromCrd()
        +getRoleArnFromConfigMap()
    }
    
    class AwsCredentialService {
        +assumeRole()
        +buildSessionTags()
    }
    
    EksAuthResource --> TokenValidationService
    EksAuthResource --> PodIdentityAssociationService
    EksAuthResource --> AwsCredentialService
```

### eks-d-auth-cli (Management Tool)

```mermaid
classDiagram
    class PodIdentityCommand {
        +run()
    }
    
    class CreateCommand {
        +run()
    }
    
    class ListCommand {
        +run()
    }
    
    class DeleteCommand {
        +run()
    }
    
    class DescribeCommand {
        +run()
    }
    
    PodIdentityCommand <|-- CreateCommand
    PodIdentityCommand <|-- ListCommand
    PodIdentityCommand <|-- DeleteCommand
    PodIdentityCommand <|-- DescribeCommand
```

### eks-pod-identity-webhook (Admission Controller)

```mermaid
classDiagram
    class WebhookEndpoint {
        +mutate()
    }
    
    class PodIdentityMutator {
        +injectTokenVolume()
        +injectEnvVars()
    }
    
    class PodIdentityAssociationLookup {
        +hasAssociation()
    }
    
    WebhookEndpoint --> PodIdentityMutator
    PodIdentityMutator --> PodIdentityAssociationLookup
```

## Design Patterns

### Dependency Injection (CDI)
- Quarkus CDI for service management
- Producer methods for AWS clients
- Scoped beans for lifecycle management

### Fallback Strategy Pattern
1. **Primary**: EKS API for pod identity associations
2. **Secondary**: Kubernetes ConfigMap
3. **Tertiary**: Generated default role ARN

### Validation Chain Pattern
1. **Request validation**: JSON structure and required fields
2. **Token validation**: Kubernetes TokenReview API
3. **Authorization**: Role mapping and AWS permissions

### Configuration Hierarchy
1. **Environment variables**: Runtime configuration
2. **Application properties**: Default settings
3. **CRD resources**: Dynamic associations
4. **ConfigMap**: Static fallback mappings

## Security Architecture

### Token Security
- JWT signature validation via Kubernetes API
- Audience validation (`pods.eks.amazonaws.com`)
- Token expiration enforcement
- Bearer token prefix handling

### AWS Security
- IAM role assumption with session tags
- Temporary credential generation
- Session duration limits (1 hour default)
- Least privilege principle

### Kubernetes Security
- Service account token validation
- RBAC for CRD access
- Admission webhook TLS
- Namespace isolation

## Scalability Considerations

### Horizontal Scaling
- Stateless service design
- Multiple proxy instances supported
- Load balancer compatible

### Performance Optimizations
- Native compilation for CLI (GraalVM)
- Async HTTP processing (Vert.x)
- Connection pooling for AWS clients
- Kubernetes client caching

### Resource Management
- Memory limits (10GB build, 8GB runtime)
- CPU limits (4 cores build)
- Container resource requests/limits
- JVM heap optimization
