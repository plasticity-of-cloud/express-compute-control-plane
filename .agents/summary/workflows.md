# Workflows

## AssumeRoleForPodIdentity Workflow

```mermaid
sequenceDiagram
    Client->>EksAuthResource: POST /<br/>AssumeRoleForPodIdentityRequest
    activate EksAuthResource
    
    EksAuthResource->>TokenValidationService: validateToken(token, clusterName)
    activate TokenValidationService
    TokenValidationService->>TokenValidationService: Remove Bearer prefix
    TokenValidationService->>TokenValidationService: Decode JWT (no verification)
    TokenValidationService->>TokenValidationService: Extract claims
    TokenValidationService-->>EksAuthResource: TokenClaims
    deactivate TokenValidationService
    
    EksAuthResource->>PodIdentityAssociationService: getRoleArn(cluster, ns, sa)
    activate PodIdentityAssociationService
    PodIdentityAssociationService->>Kubernetes API: Get ConfigMap
    activate Kubernetes API
    Kubernetes API-->>PodIdentityAssociationService: ConfigMap data
    deactivate Kubernetes API
    
    alt ConfigMap exists
        PodIdentityAssociationService->>PodIdentityAssociationService: Look up key
        alt Exact match found
            PodIdentityAssociationService-->>EksAuthResource: Role ARN
        else Namespace wildcard
            PodIdentityAssociationService-->>EksAuthResource: Role ARN
        else No match
            PodIdentityAssociationService->>PodIdentityAssociationService: Generate default ARN
            PodIdentityAssociationService-->>EksAuthResource: Default ARN
        end
    else ConfigMap missing
        PodIdentityAssociationService->>PodIdentityAssociationService: Generate default ARN
        PodIdentityAssociationService-->>EksAuthResource: Default ARN
    end
    deactivate PodIdentityAssociationService
    
    EksAuthResource->>AwsCredentialService: assumeRole(roleArn, sessionName)
    activate AwsCredentialService
    AwsCredentialService->>AWS STS: AssumeRoleRequest
    activate AWS STS
    AWS STS-->>AwsCredentialService: Credentials
    deactivate AWS STS
    AwsCredentialService->>AwsCredentialService: Log success
    AwsCredentialService-->>EksAuthResource: AWS Credentials
    deactivate AwsCredentialService
    
    EksAuthResource->>AssumeRoleForPodIdentityResponse: Build response
    EksAuthResource-->>Client: AssumeRoleForPodIdentityResponse
    deactivate EksAuthResource
```

## Token Validation Workflow

```mermaid
sequenceDiagram
    Client->>TokenValidationService: validateToken(token, clusterName)
    activate TokenValidationService
    
    TokenValidationService->>TokenValidationService: Check Bearer prefix
    alt Has prefix
        TokenValidationService->>TokenValidationService: Remove "Bearer " prefix
    end
    
    TokenValidationService->>TokenValidationService: Decode JWT
    alt Decode failed
        TokenValidationService->>TokenValidationService: Log error
        TokenValidationService-->>Client: IllegalArgumentException
        deactivate TokenValidationService
        return
    end
    
    TokenValidationService->>TokenValidationService: Extract namespace claim
    alt Namespace missing
        TokenValidationService->>TokenValidationService: Log error
        TokenValidationService-->>Client: IllegalArgumentException
        deactivate TokenValidationService
        return
    end
    
    TokenValidationService->>TokenValidationService: Extract service account claim
    alt Service account missing
        TokenValidationService->>TokenValidationService: Log error
        TokenValidationService-->>Client: IllegalArgumentException
        deactivate TokenValidationService
        return
    end
    
    TokenValidationService->>TokenValidationService: Create TokenClaims
    TokenValidationService-->>Client: TokenClaims
    deactivate TokenValidationService
```

## Role Association Lookup Workflow

```mermaid
graph TB
    A[Start] --> B[Get ConfigMap]
    B --> C{ConfigMap exists?}
    C -->|No| D[Generate Default ARN]
    C -->|Yes| E[Get ConfigMap Data]
    
    E --> F[Build exact key: cluster:ns:sa]
    F --> G[Look up exact key]
    G --> H{Found?}
    H -->|Yes| I[Return Role ARN]
    H -->|No| J[Build wildcard key: cluster:ns:*]
    
    J --> K[Look up wildcard key]
    K --> L{Found?}
    L -->|Yes| I
    L -->|No| D
    
    D --> M[Generate: arn:aws:iam::account:role/eks-pod-identity-ns-sa]
    M --> I
    I --> N[End]
```

## Health Check Workflow

```mermaid
graph LR
    A[Health Check Request] --> B{Endpoint?}
    B -->|/health/live| C[Liveness Check]
    B -->|/health/ready| D[Readiness Check]
    
    C --> E[Return Status: UP]
    D --> E
    
    E --> F[Response: {status: UP, check: liveness|ready}]
```

## CI/CD Integration Workflow

```mermaid
sequenceDiagram
    CI/CD Pipeline->>EksAuthProxy: Set environment variables
    EksAuthProxy->>EksAuthProxy: AWS_CONTAINER_CREDENTIALS_FULL_URI
    EksAuthProxy->>EksAuthProxy: AWS_CONTAINER_AUTHORIZATION_TOKEN
    
    Note over EksAuthProxy: AWS SDK automatically uses credentials
    
    AWS SDK->>EksAuthProxy: GET / (via credentials URI)
    EksAuthProxy->>EksAuthProxy: Validate token from auth header
    EksAuthProxy->>EksAuthProxy: Assume role
    EksAuthProxy-->>AWS SDK: Temporary credentials
    
    AWS SDK->>AWS Services: Use credentials for API calls
```

## Error Handling Workflow

```mermaid
graph TB
    A[Request Received] --> B{Validation}
    B -->|Invalid Token| C[400 Bad Request]
    B -->|Valid| D[Process Request]
    
    D --> E{Role Lookup}
    E -->|No Role Found| F[403 Forbidden]
    E -->|Found| G[Assume Role]
    
    G --> H{STS Call}
    H -->|Failed| I[500 Internal Error]
    H -->|Success| J[200 OK]
    
    C --> K[Return Error Response]
    F --> K
    I --> K
    J --> K
```
