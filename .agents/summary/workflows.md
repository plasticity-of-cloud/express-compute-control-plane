# Workflows and Processes

## Core Authentication Workflow

The main authentication process follows a four-step validation pattern that mirrors the AWS EKS Auth Service behavior.

### Primary Authentication Flow

```mermaid
sequenceDiagram
    participant App as AWS SDK Application
    participant Agent as EKS Pod Identity Agent
    participant Proxy as Auth Service Proxy
    participant K8s as Kubernetes API Server
    participant EKS as AWS EKS API
    participant STS as AWS STS
    
    Note over App,STS: Step 1: Request Initiation
    App->>Agent: AWS API Call (intercepted)
    Agent->>Proxy: POST / {ClusterName, Token}
    
    Note over Proxy,EKS: Step 2: Pod Identity Association Lookup
    alt Primary: EKS API Available
        Proxy->>EKS: ListPodIdentityAssociations
        EKS-->>Proxy: Association List
        Proxy->>EKS: DescribePodIdentityAssociation
        EKS-->>Proxy: Role ARN
    else Fallback: CRD Resources
        Proxy->>K8s: List PodIdentityAssociation CRDs
        K8s-->>Proxy: CRD Resources
        Proxy->>Proxy: Match by cluster/namespace/serviceaccount
    else Fallback: ConfigMap
        Proxy->>K8s: Get ConfigMap pod-identity-associations
        K8s-->>Proxy: ConfigMap Data
        Proxy->>Proxy: Pattern match (supports wildcards)
    else Fallback: Generated Default
        Proxy->>Proxy: Generate arn:aws:iam::{account}:role/eks-pod-identity-{ns}-{sa}
    end
    
    Note over Proxy,K8s: Step 3: Token Validation
    Proxy->>K8s: TokenReview API Call
    K8s->>K8s: Validate JWT signature & audience
    K8s-->>Proxy: TokenReview Response
    Proxy->>Proxy: Extract claims (namespace, serviceaccount, pod info)
    
    Note over Proxy,STS: Step 4: AWS Role Assumption
    Proxy->>Proxy: Build session tags from token claims
    Proxy->>STS: AssumeRole with session tags
    STS-->>Proxy: Temporary AWS Credentials
    
    Note over Proxy,App: Step 5: Response
    Proxy-->>Agent: Credentials Response
    Agent-->>App: AWS Credentials (transparent to app)
```

### Fallback Strategy Details

The system implements a robust fallback strategy for role association lookup:

```mermaid
graph TD
    A[Role Lookup Request] --> B{EKS API Available?}
    B -->|Yes| C[Query EKS ListPodIdentityAssociations]
    B -->|No| D[Query CRD Resources]
    
    C --> E{Association Found?}
    E -->|Yes| F[Return Role ARN from EKS]
    E -->|No| D
    
    D --> G{CRD Match Found?}
    G -->|Yes| H[Return Role ARN from CRD]
    G -->|No| I[Query ConfigMap]
    
    I --> J{ConfigMap Pattern Match?}
    J -->|Yes| K[Return Role ARN from ConfigMap]
    J -->|No| L[Generate Default Role ARN]
    
    F --> M[Continue to Token Validation]
    H --> M
    K --> M
    L --> M
```

## CLI Management Workflows

### Association Creation Workflow

```mermaid
sequenceDiagram
    participant User as CLI User
    participant CLI as eks-d-auth-cli
    participant K8s as Kubernetes API
    
    User->>CLI: create --cluster X --namespace Y --service-account Z --role-arn ARN
    CLI->>CLI: Validate input parameters
    CLI->>CLI: Build CRD resource specification
    CLI->>K8s: Create PodIdentityAssociation CRD
    K8s-->>CLI: Creation confirmation
    CLI-->>User: Success message with association details
```

### Association Listing Workflow

```mermaid
sequenceDiagram
    participant User as CLI User
    participant CLI as eks-d-auth-cli
    participant K8s as Kubernetes API
    
    User->>CLI: list --cluster X [--namespace Y]
    CLI->>K8s: List PodIdentityAssociation CRDs
    alt Namespace Filter Applied
        K8s-->>CLI: Filtered CRD list
    else No Filter
        K8s-->>CLI: All CRDs for cluster
    end
    CLI->>CLI: Format output table
    CLI-->>User: Formatted association list
```

## Webhook Mutation Workflow

### Pod Admission Process

```mermaid
sequenceDiagram
    participant K8s as Kubernetes API Server
    participant Webhook as Pod Identity Webhook
    participant CRD as CRD Resources
    
    Note over K8s,CRD: Pod Creation Intercepted
    K8s->>Webhook: AdmissionReview (Pod CREATE)
    Webhook->>Webhook: Extract pod serviceAccount
    Webhook->>CRD: Check for PodIdentityAssociation
    
    alt Association Exists
        CRD-->>Webhook: Association found
        Webhook->>Webhook: Generate JSON patches
        Note over Webhook: Inject AWS_CONTAINER_CREDENTIALS_FULL_URI
        Note over Webhook: Add service account token volume
        Webhook-->>K8s: AdmissionReview (allowed=true, patches)
        K8s->>K8s: Apply patches to pod spec
    else No Association
        CRD-->>Webhook: No association found
        Webhook-->>K8s: AdmissionReview (allowed=true, no patches)
        K8s->>K8s: Create pod without modifications
    end
```

### Mutation Details

The webhook applies specific mutations to pods with associated service accounts:

**Environment Variable Injection**:
```yaml
env:
- name: AWS_CONTAINER_CREDENTIALS_FULL_URI
  value: "http://eks-pod-identity-agent.kube-system:80/v1/credentials"
- name: AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE
  value: "/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/token"
```

**Volume and Volume Mount Injection**:
```yaml
volumes:
- name: aws-iam-token
  projected:
    sources:
    - serviceAccountToken:
        audience: pods.eks.amazonaws.com
        expirationSeconds: 86400
        path: token

volumeMounts:
- name: aws-iam-token
  mountPath: /var/run/secrets/pods.eks.amazonaws.com/serviceaccount
  readOnly: true
```

## Build and Deployment Workflows

### Multi-Module Build Process

```mermaid
graph TB
    A[build.sh] --> B{Target Selection}
    B -->|all| C[Build All Modules]
    B -->|proxy| D[Build eks-auth-proxy]
    B -->|cli| E[Build eks-d-auth-cli]
    B -->|webhook| F[Build eks-pod-identity-webhook]
    
    C --> G[Build CRD Module]
    D --> G
    E --> G
    F --> G
    
    G --> H{Build Type}
    H -->|JVM| I[Maven Package]
    H -->|Native| J[GraalVM Native Build]
    
    I --> K[Jib Container Build]
    J --> L[Native Container Build]
    
    K --> M{Push Enabled?}
    L --> M
    M -->|Yes| N[Push to Registry]
    M -->|No| O[Local Image Only]
```

### Deployment Process

```mermaid
sequenceDiagram
    participant User as Operator
    participant Deploy as deploy.sh
    participant K8s as Kubernetes Cluster
    participant CertMgr as Cert Manager
    
    User->>Deploy: ./deploy.sh --cluster X --region Y
    
    Note over Deploy,CertMgr: Step 1: Prerequisites
    Deploy->>K8s: Check cert-manager installation
    alt Cert Manager Missing
        Deploy->>K8s: Install cert-manager
        Deploy->>Deploy: Wait for cert-manager ready
    end
    
    Note over Deploy,K8s: Step 2: CRD Installation
    Deploy->>K8s: Apply PodIdentityAssociation CRD
    
    Note over Deploy,K8s: Step 3: Auth Proxy Deployment
    Deploy->>K8s: Apply proxy deployment & service
    Deploy->>K8s: Apply RBAC resources
    
    Note over Deploy,K8s: Step 4: CLI Deployment
    Deploy->>K8s: Apply CLI job/deployment
    
    Note over Deploy,K8s: Step 5: Webhook Deployment
    Deploy->>K8s: Apply webhook deployment
    Deploy->>K8s: Apply MutatingAdmissionWebhook
    Deploy->>CertMgr: Request TLS certificate
    
    Note over Deploy,K8s: Step 6: Sample Associations
    Deploy->>K8s: Apply sample PodIdentityAssociation CRDs
    
    Deploy-->>User: Deployment complete
```

## Error Handling Workflows

### Authentication Error Flow

```mermaid
graph TD
    A[Authentication Request] --> B{Request Valid?}
    B -->|No| C[Return 400 Bad Request]
    B -->|Yes| D[Token Validation]
    
    D --> E{Token Valid?}
    E -->|No| F[Return 400 Token Invalid]
    E -->|Yes| G[Role Association Lookup]
    
    G --> H{Association Found?}
    H -->|No| I[Return 400 No Association]
    H -->|Yes| J[AWS STS AssumeRole]
    
    J --> K{STS Success?}
    K -->|No| L[Return 500 STS Error]
    K -->|Yes| M[Return 200 Credentials]
```

### CLI Error Handling

```mermaid
graph TD
    A[CLI Command] --> B{Input Valid?}
    B -->|No| C[Print Usage & Exit 1]
    B -->|Yes| D[Kubernetes API Call]
    
    D --> E{API Success?}
    E -->|No| F{Network Error?}
    F -->|Yes| G[Print Connection Error & Exit 1]
    F -->|No| H[Print API Error & Exit 1]
    E -->|Yes| I[Process Response]
    
    I --> J{Operation Success?}
    J -->|No| K[Print Operation Error & Exit 1]
    J -->|Yes| L[Print Success & Exit 0]
```

## Configuration Management Workflows

### Dynamic Configuration Updates

```mermaid
sequenceDiagram
    participant Admin as Administrator
    participant CLI as eks-d-auth-cli
    participant K8s as Kubernetes API
    participant Proxy as Auth Service Proxy
    
    Note over Admin,Proxy: Runtime Configuration Update
    Admin->>CLI: create/update/delete association
    CLI->>K8s: Modify CRD resource
    K8s-->>CLI: Confirmation
    
    Note over K8s,Proxy: Next Authentication Request
    Proxy->>K8s: Query CRD resources
    K8s-->>Proxy: Updated association list
    Proxy->>Proxy: Use new configuration
```

### ConfigMap Fallback Management

```mermaid
sequenceDiagram
    participant Admin as Administrator
    participant K8s as Kubernetes API
    participant Proxy as Auth Service Proxy
    
    Admin->>K8s: Update ConfigMap pod-identity-associations
    K8s-->>Admin: ConfigMap updated
    
    Note over Proxy: Next Authentication (CRD lookup fails)
    Proxy->>K8s: Get ConfigMap
    K8s-->>Proxy: Updated ConfigMap data
    Proxy->>Proxy: Parse key patterns & wildcards
```

## Monitoring and Observability Workflows

### Health Check Process

```mermaid
sequenceDiagram
    participant K8s as Kubernetes
    participant Proxy as Auth Service Proxy
    participant Deps as Dependencies
    
    loop Every 10 seconds
        K8s->>Proxy: GET /health/live
        Proxy-->>K8s: 200 OK (service running)
    end
    
    loop Every 30 seconds
        K8s->>Proxy: GET /health/ready
        Proxy->>Deps: Check AWS connectivity
        Proxy->>Deps: Check Kubernetes connectivity
        alt All Dependencies OK
            Proxy-->>K8s: 200 OK (ready)
        else Dependency Issue
            Proxy-->>K8s: 503 Service Unavailable
        end
    end
```

### Metrics Collection

```mermaid
graph LR
    A[HTTP Requests] --> B[Micrometer Metrics]
    C[JVM Metrics] --> B
    D[Custom Business Metrics] --> B
    B --> E[Prometheus Format]
    E --> F[GET /metrics]
    F --> G[Prometheus Scraper]
```

## Integration Workflows

### EKS Pod Identity Agent Integration

```mermaid
sequenceDiagram
    participant App as Application
    participant Agent as EKS Pod Identity Agent
    participant Proxy as Auth Service Proxy
    
    Note over App,Proxy: Transparent AWS SDK Integration
    App->>Agent: AWS API call (intercepted by agent)
    Agent->>Agent: Check credential cache
    alt Credentials Cached & Valid
        Agent-->>App: Return cached credentials
    else Need New Credentials
        Agent->>Proxy: POST / with service account token
        Proxy-->>Agent: New AWS credentials
        Agent->>Agent: Cache credentials
        Agent-->>App: Return new credentials
    end
```

This integration allows existing AWS SDK applications to work without code changes, as the EKS Pod Identity Agent transparently handles credential acquisition and caching.
