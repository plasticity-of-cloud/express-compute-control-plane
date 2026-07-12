# Workflows

## Credential Exchange (Hot Path)

```mermaid
sequenceDiagram
    participant Pod
    participant Agent as Pod Identity Agent
    participant Proxy as eks-dx-auth-proxy
    participant K8s as K8s API Server
    participant Lambda as credential-service
    participant DDB as DynamoDB
    participant STS as AWS STS

    Pod->>Agent: Request credentials
    Agent->>Proxy: POST /clusters/{name}/assets {token}
    Proxy->>K8s: TokenReview (audience: pods.eks.amazonaws.com)
    K8s-->>Proxy: Authenticated (claims)
    Proxy->>Lambda: Forward token + claims
    Lambda->>DDB: Fetch JWKS for cluster
    Lambda->>Lambda: Validate JWT signature
    Lambda->>DDB: GetItem CLUSTER#name / ns#sa
    Lambda->>STS: AssumeRole(roleArn, sessionTags)
    STS-->>Lambda: Temporary credentials
    Lambda-->>Proxy: {AccessKeyId, SecretAccessKey, Token, Expiration}
    Proxy-->>Agent: Credentials
    Agent-->>Pod: AWS credentials available
```

## Managed Cluster Provisioning

```mermaid
sequenceDiagram
    participant CLI as eks-dx CLI
    participant TSvc as tenant-service
    participant DDB as DynamoDB
    participant Net as TenantNetworkService
    participant Crypto as TenantCryptoService
    participant IAM as TenantIamService
    participant SQS as AWS SQS
    participant EC2 as TenantEc2Service
    participant DLM as TenantDlmService

    CLI->>TSvc: POST /clusters {name, managed:true}
    TSvc->>DDB: Check cluster name uniqueness
    TSvc->>DDB: Write initial record (PROVISIONING, 0%)
    TSvc->>SQS: Create progress queue
    TSvc->>Net: createTenantNetwork()
    Note over Net: Subnet + Security Group + Route Table
    TSvc->>Crypto: generateAndStore()
    Note over Crypto: CA cert (KMS-signed) + SA keys + JWKS
    TSvc->>IAM: createTenantRole()
    Note over IAM: Role + inline policy + instance profile
    TSvc->>SQS: Create interruption queue
    TSvc->>TSvc: Create EventBridge rules
    TSvc->>DLM: createEtcdBackupPolicy()
    TSvc->>EC2: launchInstance()
    Note over EC2: Launch + associate EIP
    TSvc->>DDB: Pre-register cluster (JWKS in DDB)
    TSvc-->>CLI: 202 Accepted {tenantId, clusterName}
    CLI->>TSvc: GET /tenants/{id}/stream (SSE)
    TSvc->>SQS: Poll progress queue
    Note over TSvc: Stream progress events to CLI
```

## Provisioning Rollback (Failure Compensation)

```mermaid
flowchart TD
    A[Provisioning starts] --> B{Network created?}
    B -->|Yes| C{Crypto generated?}
    B -->|No| Z[Done - nothing to clean]
    C -->|Yes| D{IAM created?}
    C -->|No| E[Delete subnet + SG]
    D -->|Yes| F{EC2 launched?}
    D -->|No| G[Delete secrets + subnet + SG]
    F -->|Yes| H[Terminate instance]
    F -->|No| I[Delete role + secrets + subnet + SG]
    H --> J[Release EIP]
    J --> K[Delete SQS queues]
    K --> L[Delete EventBridge rules]
    L --> M[Delete DLM policy]
    M --> N[Delete IAM role]
    N --> O[Delete secrets]
    O --> P[Delete subnet + SG]
    P --> Z
```

## Self-Managed Cluster Registration

```mermaid
sequenceDiagram
    participant CLI as eks-dx CLI
    participant K8s as Target Cluster
    participant TSvc as tenant-service
    participant DDB as DynamoDB

    CLI->>K8s: Fetch OIDC discovery + JWKS
    CLI->>TSvc: POST /clusters {name, jwks, issuer}
    Note over TSvc: jwks present → self-managed mode
    TSvc->>DDB: Check uniqueness
    TSvc->>DDB: Write cluster record + associations table
    TSvc-->>CLI: 201 Created
```

## Cluster Deletion

```mermaid
flowchart TD
    A[DELETE /clusters/name] --> B{managed?}
    B -->|Yes| C[Full deprovision]
    C --> D[Terminate EC2]
    D --> E[Release EIP]
    E --> F[Delete SQS queues]
    F --> G[Delete EventBridge rules]
    G --> H[Delete DLM snapshots + policy]
    H --> I[Delete IAM role + instance profile]
    I --> J[Delete network subnet + SG]
    J --> K[Delete Secrets Manager secrets]
    K --> L[Deregister from DynamoDB]
    B -->|No| L
    L --> M[Done]
```

## Pod Identity Webhook Flow

```mermaid
sequenceDiagram
    participant K8s as K8s API Server
    participant WH as Pod Identity Webhook
    participant Lambda as mgmt-service

    K8s->>WH: AdmissionReview (Pod CREATE)
    WH->>Lambda: Check association exists (ns + sa)
    Lambda-->>WH: Association found (roleArn)
    WH->>WH: Build JSON patches
    Note over WH: 1. Add env vars<br/>2. Add projected token volume<br/>3. Add volume mount
    WH-->>K8s: AdmissionResponse (patches)
```

## EC2NodeClass Webhook Flow (Karpenter)

```mermaid
sequenceDiagram
    participant K8s as K8s API Server
    participant WH as Karpenter Support Webhook
    participant CI as ClusterIdentityService

    K8s->>WH: AdmissionReview (EC2NodeClass CREATE/UPDATE)
    WH->>CI: Get cluster identity
    CI-->>WH: {endpoint, CA, serviceCIDR, clusterName}
    WH->>WH: Rewrite amiFamily → Custom
    WH->>WH: Merge userData (MIME multipart)
    WH->>WH: Inject cluster tags
    WH-->>K8s: AdmissionResponse (mutated spec)
```

## Stop/Resume Lifecycle

```mermaid
stateDiagram-v2
    [*] --> PROVISIONING
    PROVISIONING --> READY : Boot complete
    PROVISIONING --> FAILED : Error
    READY --> STOPPING : stop-cluster
    STOPPING --> STOPPED : Instance stopped
    STOPPED --> RESUMING : resume-cluster
    RESUMING --> READY : Instance running + IP
    FAILED --> [*] : delete-cluster
    READY --> [*] : delete-cluster
    STOPPED --> [*] : delete-cluster
```
