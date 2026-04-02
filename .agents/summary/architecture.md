# Architecture Overview

## System Architecture

```mermaid
graph TB
    subgraph Client
        A[Application]
    end
    
    subgraph Service
        B[EksAuthResource]
        C[TokenValidationService]
        D[PodIdentityAssociationService]
        E[AwsCredentialService]
    end
    
    subgraph External
        F[Kubernetes API]
        G[AWS STS]
    end
    
    A -->|POST /| B
    B -->|Validate Token| C
    B -->|Lookup Role| D
    B -->|Assume Role| E
    D -->|Read ConfigMap| F
    E -->|AssumeRole| G
```

## Component Relationships

```mermaid
classDiagram
    class EksAuthResource {
        +assumeRoleForPodIdentity(Request)
    }
    
    class TokenValidationService {
        +validateToken(token, clusterName)
        +TokenClaims
    }
    
    class PodIdentityAssociationService {
        +getRoleArnForServiceAccount(cluster, ns, sa)
        +generateAssociationId(cluster, ns, sa)
    }
    
    class AwsCredentialService {
        +assumeRole(roleArn, sessionName)
        +generateSessionName(ns, sa)
    }
    
    class AssumeRoleForPodIdentityRequest {
        +clusterName
        +token
    }
    
    class AssumeRoleForPodIdentityResponse {
        +credentials
        +assumedRoleUser
        +podIdentityAssociation
        +subject
        +audience
    }
    
    EksAuthResource --> TokenValidationService
    EksAuthResource --> PodIdentityAssociationService
    EksAuthResource --> AwsCredentialService
    PodIdentityAssociationService --> ConfigMap
```

## Data Flow

```mermaid
sequenceDiagram
    Client->>EksAuthResource: POST / with Token
    EksAuthResource->>TokenValidationService: validateToken()
    TokenValidationService->>TokenValidationService: Decode JWT
    TokenValidationService-->>EksAuthResource: TokenClaims
    
    EksAuthResource->>PodIdentityAssociationService: getRoleArn()
    PodIdentityAssociationService->>Kubernetes API: Get ConfigMap
    PodIdentityAssociationService-->>EksAuthResource: Role ARN
    
    EksAuthResource->>AwsCredentialService: assumeRole()
    AwsCredentialService->>AWS STS: AssumeRoleRequest
    AWS STS-->>AwsCredentialService: Credentials
    AwsCredentialService-->>EksAuthResource: AWS Credentials
    
    EksAuthResource-->>Client: AssumeRoleForPodIdentityResponse
```

## Design Patterns

| Pattern | Usage |
|---------|-------|
| RESTful API | HTTP endpoints for AssumeRoleForPodIdentity |
| Dependency Injection | CDI @ApplicationScoped services |
| Service Layer | Separation of concerns in service layer |
| DTO Pattern | Request/Response models |
| Health Checks | Quarkus SmallRye Health |
| Metrics | Micrometer Prometheus registry |

## Security Architecture

```mermaid
graph LR
    A[JWT Token] --> B[TokenValidationService]
    B --> C{Valid?}
    C -->|No| D[400 Bad Request]
    C -->|Yes| E[PodIdentityAssociationService]
    E --> F{Role Found?}
    F -->|No| G[Default Role ARN]
    F -->|Yes| H[Role ARN]
    G --> I[AwsCredentialService]
    H --> I
    I --> J[AWS STS]
    J --> K[Cached Credentials]
```

## Deployment Architecture

```mermaid
graph TB
    subgraph Kubernetes
        subgraph kube-system
            L[pod-identity-associations ConfigMap]
        end
        
        subgraph Application Namespace
            M[EKS Auth Proxy Pod]
        end
        
        M -->|Read| L
    end
    
    subgraph AWS Cloud
        N[AWS STS]
    end
    
    M -->|AssumeRole| N
```
