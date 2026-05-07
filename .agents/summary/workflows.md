# Key Workflows and Processes

## Authentication Workflow

The core authentication workflow enables Kubernetes pods to obtain AWS credentials using service account tokens.

```mermaid
sequenceDiagram
    participant Pod as Application Pod
    participant Proxy as EKS Auth Proxy
    participant K8s as Kubernetes API
    participant Lambda as EKS-DX Lambda
    participant DDB as DynamoDB
    participant STS as AWS STS
    
    Note over Pod: Pod starts with projected SA token
    Pod->>Proxy: POST / {ClusterName, Token}
    
    Note over Proxy: Fast-fail validation
    Proxy->>K8s: TokenReview {token, audience}
    K8s-->>Proxy: {authenticated: true, user: {...}}
    
    Note over Proxy: Forward to Lambda if valid
    Proxy->>Lambda: POST /clusters/{name}/assets
    
    Note over Lambda: Multi-stage validation
    Lambda->>Lambda: Parse JWT claims
    Lambda->>DDB: Get cluster JWKS
    Lambda->>Lambda: Verify JWT signature
    Lambda->>DDB: Lookup association
    
    Note over Lambda: AWS credential exchange
    Lambda->>STS: AssumeRole with session tags
    STS-->>Lambda: Temporary credentials
    
    Lambda-->>Proxy: AWS credentials response
    Proxy-->>Pod: Credentials {AccessKey, SecretKey, SessionToken}
```

### Authentication Steps

1. **Token Acquisition**: Pod uses projected service account token with audience `pods.eks.amazonaws.com`
2. **Proxy Validation**: EKS Auth Proxy performs TokenReview with Kubernetes API for fast-fail
3. **Token Forwarding**: Valid tokens are forwarded to Lambda service
4. **JWT Validation**: Lambda validates JWT signature using cached JWKS
5. **Association Lookup**: Lambda queries DynamoDB for pod identity association
6. **Credential Exchange**: Lambda calls STS AssumeRole with Kubernetes metadata as session tags
7. **Response**: AWS credentials returned to pod for use with AWS SDKs

## Cluster Management Workflow

Cluster registration enables the system to validate tokens from specific Kubernetes clusters.

```mermaid
sequenceDiagram
    participant CLI as EKS-DX CLI
    participant K8s as Kubernetes API
    participant Lambda as EKS-DX Lambda
    participant DDB as DynamoDB
    
    Note over CLI: Cluster registration
    CLI->>K8s: GET /.well-known/openid_configuration
    K8s-->>CLI: {issuer, jwks_uri}
    CLI->>K8s: GET {jwks_uri}
    K8s-->>CLI: JWKS {keys: [...]}
    
    CLI->>Lambda: POST /clusters {name, issuer, jwks}
    Lambda->>Lambda: Validate cluster data
    Lambda->>DDB: Store cluster info
    Lambda-->>CLI: 201 Created
    
    Note over CLI: JWKS refresh (optional)
    CLI->>Lambda: PUT /clusters/{name}/jwks
    Lambda->>K8s: Fetch fresh JWKS
    Lambda->>DDB: Update cluster JWKS
    Lambda-->>CLI: 200 OK
```

### Cluster Management Steps

1. **OIDC Discovery**: CLI discovers OIDC configuration from Kubernetes API
2. **JWKS Retrieval**: CLI fetches JSON Web Key Set for token validation
3. **Registration**: CLI registers cluster with Lambda service
4. **Validation**: Lambda validates cluster data and stores in DynamoDB
5. **JWKS Refresh**: Periodic JWKS updates to handle key rotation

## Association Management Workflow

Pod identity associations map Kubernetes service accounts to AWS IAM roles.

```mermaid
sequenceDiagram
    participant CLI as EKS-DX CLI
    participant Lambda as EKS-DX Lambda
    participant IAM as AWS IAM
    participant DDB as DynamoDB
    
    Note over CLI: Create association
    CLI->>Lambda: POST /clusters/{name}/associations
    Note right of CLI: {namespace, serviceAccount, roleArn}
    
    Lambda->>IAM: GetRole {RoleName}
    IAM-->>Lambda: Role details + trust policy
    Lambda->>Lambda: Validate trust policy
    
    Lambda->>DDB: Check for duplicate
    Lambda->>DDB: Store association
    Lambda-->>CLI: 201 Created {associationId}
    
    Note over CLI: List associations
    CLI->>Lambda: GET /clusters/{name}/associations?namespace=default
    Lambda->>DDB: Query associations
    Lambda-->>CLI: Association list
```

### Association Management Steps

1. **Role Validation**: Lambda validates IAM role exists and has proper trust policy
2. **Duplicate Check**: Lambda ensures no duplicate associations exist
3. **Storage**: Association stored in DynamoDB with generated ID
4. **Querying**: Associations can be filtered by namespace and service account

## Pod Identity Injection Workflow

The admission webhook automatically injects identity configuration into pods.

```mermaid
sequenceDiagram
    participant K8s as Kubernetes API
    participant Webhook as Pod Identity Webhook
    participant Lambda as EKS-DX Lambda
    participant Pod as Application Pod
    
    Note over K8s: Pod creation request
    K8s->>Webhook: AdmissionReview {pod spec}
    
    Webhook->>Lambda: Check association exists
    Lambda-->>Webhook: Association found/not found
    
    alt Association exists
        Webhook->>Webhook: Inject environment variables
        Note right of Webhook: AWS_ROLE_ARN, AWS_WEB_IDENTITY_TOKEN_FILE
        Webhook->>Webhook: Add projected token volume
        Webhook-->>K8s: Mutated pod spec
    else No association
        Webhook-->>K8s: Original pod spec (no changes)
    end
    
    K8s->>Pod: Create pod with identity (if injected)
```

### Pod Injection Steps

1. **Admission Review**: Kubernetes sends pod creation request to webhook
2. **Association Check**: Webhook queries Lambda for existing association
3. **Conditional Mutation**: Pod is mutated only if association exists
4. **Environment Injection**: AWS_ROLE_ARN and AWS_WEB_IDENTITY_TOKEN_FILE added
5. **Volume Mounting**: Projected service account token volume mounted

## Error Handling Workflows

### Authentication Error Flow
```mermaid
flowchart TD
    A[Token Request] --> B{TokenReview Valid?}
    B -->|No| C[Return 400 Bad Request]
    B -->|Yes| D{JWT Signature Valid?}
    D -->|No| E[Return 403 Forbidden]
    D -->|Yes| F{Association Exists?}
    F -->|No| G[Return 404 Not Found]
    F -->|Yes| H{STS AssumeRole Success?}
    H -->|No| I[Return 500 Internal Error]
    H -->|Yes| J[Return 200 with Credentials]
```

### Management Error Flow
```mermaid
flowchart TD
    A[Management Request] --> B{Authentication Valid?}
    B -->|No| C[Return 401 Unauthorized]
    B -->|Yes| D{Input Validation?}
    D -->|No| E[Return 400 Bad Request]
    D -->|Yes| F{Resource Exists?}
    F -->|No| G[Return 404 Not Found]
    F -->|Yes| H{Operation Success?}
    H -->|No| I[Return 500 Internal Error]
    H -->|Yes| J[Return Success Response]
```

## Configuration Workflows

### CLI Configuration Setup
```mermaid
sequenceDiagram
    participant User
    participant CLI as EKS-DX CLI
    participant Config as ~/.eks-dx/config
    
    User->>CLI: eks-dx configure --endpoint URL --region REGION
    CLI->>Config: Create/update config file
    CLI-->>User: Configuration saved
    
    Note over CLI: Subsequent commands
    CLI->>Config: Load configuration
    CLI->>CLI: Merge with CLI flags and env vars
```

### Environment Variable Resolution
```mermaid
flowchart TD
    A[Component Startup] --> B[Load application.properties]
    B --> C[Override with environment variables]
    C --> D[Override with CLI flags]
    D --> E[Final configuration]
```

## Monitoring and Observability Workflows

### CloudWatch Metrics Flow
```mermaid
sequenceDiagram
    participant Lambda as EKS-DX Lambda
    participant CW as CloudWatch
    participant Alarms as CloudWatch Alarms
    participant SNS as SNS Topic
    
    Lambda->>CW: Emit custom metrics
    Note right of Lambda: Authentication success/failure rates
    CW->>Alarms: Evaluate alarm conditions
    
    alt Threshold breached
        Alarms->>SNS: Send notification
        SNS-->>Admin: Alert notification
    end
```

### Logging Workflow
```mermaid
flowchart TD
    A[Component Operation] --> B[Structured Logging]
    B --> C[CloudWatch Logs]
    C --> D[Log Aggregation]
    D --> E[Monitoring Dashboard]
    E --> F[Alerting Rules]
```

## Deployment Workflows

### SAM Deployment
```bash
# Build and deploy workflow
mvn -pl eks-dx-lambda package -DskipTests
sam validate
sam deploy --guided
```

### CDK Deployment
```bash
# Infrastructure as Code workflow
mvn -pl eks-dx-lambda package -DskipTests
cd infra
cdk bootstrap  # First time only
cdk deploy
```

### Container Deployment
```bash
# Build container images
mvn -pl eks-dx-auth-proxy package -DskipTests \
  -Dquarkus.container-image.build=true

mvn -pl eks-dx-pod-identity-webhook package -DskipTests \
  -Dquarkus.container-image.build=true

# Deploy to Kubernetes
kubectl apply -f k8s-manifests/
```

## Testing Workflows

### Unit Testing
```bash
# Run all unit tests
mvn test

# Run specific module tests
mvn -pl eks-dx-lambda test
```

### Integration Testing
```bash
# Start DynamoDB Local
docker run -d -p 18000:8000 \
  public.ecr.aws/aws-dynamodb-local/aws-dynamodb-local:latest

# Run integration tests
mvn -pl eks-dx-lambda test \
  -Dtest=DynamoDbIntegrationTest \
  -Dintegration.dynamodb=true
```

### End-to-End Testing
```bash
# Full integration test with real AWS resources
mvn -pl eks-dx-auth-proxy test \
  -Dintegration.aws=true \
  -Dintegration.cluster=my-cluster \
  -Daws.region=us-east-1
```
