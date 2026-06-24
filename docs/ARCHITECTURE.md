# EKS-DX Architecture

## 1. System Overview

```mermaid
graph TB
    subgraph "Kubernetes Cluster (k3s / microk8s / EKS-D)"
        Pod["Application Pod"]
        Agent["EKS Pod Identity Agent<br/>(DaemonSet, 169.254.170.23)"]
        Proxy["eks-dx-auth-proxy<br/>(TokenReview + forwarding)"]
        Webhook["eks-dx-pod-identity-webhook<br/>(Admission Controller)"]
        KubeAPI["Kubernetes API Server"]
    end

    subgraph "Developer Machine"
        CLI["eks-dx CLI<br/>(native binary)"]
    end

    subgraph "AWS (Serverless)"
        APIGW["API Gateway<br/>(IAM auth on mgmt, open on /assets)"]
        Lambda["eks-dx-lambda<br/>(Java 21, SnapStart)"]
        DDBClusters["DynamoDB<br/>eks-dx-clusters"]
        DDBAssoc["DynamoDB<br/>eks-dx-associations"]
        STS["AWS STS<br/>(AssumeRole)"]
        CW["CloudWatch<br/>(Alarms + Logs)"]
    end

    Pod -->|"1. AWS SDK call"| Agent
    Agent -->|"2. POST /clusters/{name}/assets"| Proxy
    Proxy -->|"3. TokenReview"| KubeAPI
    Proxy -->|"4. Forward request"| APIGW
    APIGW --> Lambda
    Lambda -->|"5. JWKS validation"| DDBClusters
    Lambda -->|"6. Association lookup"| DDBAssoc
    Lambda -->|"7. AssumeRole"| STS
    Lambda -->|"8. Credentials"| Proxy
    Proxy -->|"9. Credentials"| Agent
    Agent -->|"10. Credentials"| Pod

    Webhook -->|"Association lookup"| APIGW
    KubeAPI -->|"AdmissionReview"| Webhook

    CLI -->|"SigV4-signed requests"| APIGW
    CLI -->|"Read JWKS + OIDC"| KubeAPI

    Lambda -.->|"Metrics + Logs"| CW
    APIGW -.->|"Access Logs"| CW
```

## 2. Credential Exchange Flow (Main Use Case)

```mermaid
sequenceDiagram
    participant Pod as Application Pod
    participant Agent as Pod Identity Agent
    participant Proxy as eks-dx-auth-proxy
    participant K8s as K8s API Server
    participant GW as API Gateway
    participant Lambda as eks-dx-lambda
    participant DDB as DynamoDB
    participant STS as AWS STS

    Pod->>Agent: AWS SDK → GET /v1/credentials
    Agent->>Proxy: POST /clusters/{name}/assets<br/>{"token": "<SA JWT>"}

    rect rgb(230, 245, 255)
        Note over Proxy,K8s: Fast-fail validation (in-cluster)
        Proxy->>K8s: TokenReview (audience: pods.eks.amazonaws.com)
        K8s-->>Proxy: Authenticated ✓ (namespace, SA, pod)
    end

    Proxy->>GW: Forward POST /clusters/{name}/assets
    GW->>Lambda: Invoke

    rect rgb(255, 245, 230)
        Note over Lambda,STS: Credential exchange (serverless)
        Lambda->>DDB: GetItem(clusterName) → JWKS + issuer
        Lambda->>Lambda: jose4j JWT validation (sig, aud, iss, exp)
        Lambda->>DDB: GetItem(CLUSTER#name, ns#sa) → roleArn
        Lambda->>STS: AssumeRole(roleArn, sessionTags)
        STS-->>Lambda: Temporary credentials
    end

    Lambda-->>GW: 200 {credentials, subject, association}
    GW-->>Proxy: Response
    Proxy-->>Agent: Response
    Agent-->>Pod: AWS credentials (accessKeyId, secretAccessKey, sessionToken)
```

## 3. Cluster Registration Flow

```mermaid
sequenceDiagram
    participant User as Developer
    participant CLI as eks-dx CLI
    participant K8s as K8s API Server
    participant GW as API Gateway (IAM auth)
    participant Lambda as eks-dx-lambda
    participant DDB as DynamoDB (eks-dx-clusters)

    User->>CLI: eks-dx register-cluster --name my-k3s --region us-east-1
    CLI->>K8s: GET /openid/v1/jwks
    K8s-->>CLI: JWKS JSON (public keys)
    CLI->>K8s: GET /.well-known/openid-configuration
    K8s-->>CLI: {"issuer": "https://..."}
    CLI->>CLI: Parse issuer from OIDC config
    CLI->>CLI: Sign request with AWS SigV4
    CLI->>GW: POST /clusters<br/>{"name", "issuer", "jwks"}
    GW->>GW: Verify IAM signature
    GW->>Lambda: Invoke
    Lambda->>DDB: PutItem(clusterName, issuer, jwks, timestamps)
    DDB-->>Lambda: OK
    Lambda-->>GW: 201 Created
    GW-->>CLI: Response
    CLI-->>User: ✓ Cluster "my-k3s" registered
```

## 4. Pod Identity Association Management

```mermaid
sequenceDiagram
    participant User as Developer
    participant CLI as eks-dx CLI
    participant GW as API Gateway (IAM auth)
    participant Lambda as eks-dx-lambda
    participant DDB as DynamoDB (eks-dx-associations)

    User->>CLI: eks-dx create-association<br/>--cluster-name my-k3s<br/>--namespace default --service-account my-app<br/>--role-arn arn:aws:iam::...:role/my-role

    CLI->>GW: POST /clusters/my-k3s/pod-identity-associations<br/>(SigV4 signed)
    GW->>Lambda: Invoke
    Lambda->>DDB: GetItem(CLUSTER#my-k3s, default#my-app)
    DDB-->>Lambda: Not found (no duplicate)
    Lambda->>DDB: PutItem(PK=CLUSTER#my-k3s, SK=default#my-app,<br/>roleArn, associationId=assoc-xxx)
    DDB-->>Lambda: OK
    Lambda-->>GW: 201 {associationId, namespace, serviceAccount, roleArn}
    GW-->>CLI: Response
    CLI-->>User: ✓ Association created: default/my-app → role
```

## 5. Webhook Pod Mutation Flow

```mermaid
sequenceDiagram
    participant K8s as K8s API Server
    participant WH as eks-dx-pod-identity-webhook
    participant GW as API Gateway
    participant Lambda as eks-dx-lambda

    K8s->>WH: AdmissionReview (CREATE Pod)<br/>namespace=default, sa=my-app

    WH->>GW: GET /clusters/{name}/pod-identity-associations<br/>?namespace=default&serviceAccount=my-app<br/>(Bearer: projected SA token)
    GW->>Lambda: Invoke
    Lambda-->>GW: 200 {associations: [{...}]}
    GW-->>WH: Association found ✓

    rect rgb(230, 255, 230)
        Note over WH: Mutate pod spec
        WH->>WH: Inject AWS_CONTAINER_CREDENTIALS_FULL_URI
        WH->>WH: Inject AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE
        WH->>WH: Add projected SA token volume<br/>(audience: pods.eks.amazonaws.com)
    end

    WH-->>K8s: AdmissionResponse (allowed=true, patch=[...])
```

## 6. Token Validation State Machine

```mermaid
stateDiagram-v2
    [*] --> Received: Token arrives at proxy

    Received --> StripBearer: Has "Bearer " prefix?
    Received --> TokenReview: No prefix

    StripBearer --> TokenReview: Strip prefix

    TokenReview --> Authenticated: K8s API returns authenticated=true
    TokenReview --> Rejected_TokenReview: authenticated=false or API error

    Authenticated --> Forwarded: Forward to Lambda

    Forwarded --> JWKS_Lookup: Lambda receives token
    JWKS_Lookup --> JWKS_Cached: Cache hit (< 5 min)
    JWKS_Lookup --> JWKS_Fetch: Cache miss
    JWKS_Fetch --> JWKS_Cached: Load from DynamoDB

    JWKS_Cached --> JWT_Validate: Verify signature + claims
    JWT_Validate --> Claims_Extracted: Valid (sig, aud, iss, exp)
    JWT_Validate --> Rejected_JWT: Invalid signature/audience/issuer/expired

    Claims_Extracted --> Association_Lookup: Extract namespace + SA
    Association_Lookup --> Role_Found: DynamoDB has mapping
    Association_Lookup --> Rejected_NoAssoc: No association

    Role_Found --> STS_AssumeRole: AssumeRole with session tags
    STS_AssumeRole --> Credentials_Returned: Success
    STS_AssumeRole --> Rejected_STS: STS error

    Credentials_Returned --> [*]: 200 + credentials

    Rejected_TokenReview --> [*]: 400 Invalid token
    Rejected_JWT --> [*]: 400 Invalid token
    Rejected_NoAssoc --> [*]: 404 No association
    Rejected_STS --> [*]: 500 Internal error
```

## 7. DynamoDB Data Model

```mermaid
erDiagram
    CLUSTERS {
        string clusterName PK "Partition key"
        string issuer "OIDC issuer URL"
        string jwks "JSON Web Key Set (public keys)"
        string createdAt "ISO 8601 timestamp"
        string updatedAt "ISO 8601 timestamp"
    }

    ASSOCIATIONS {
        string partitionKey PK "CLUSTER#{clusterName}"
        string sortKey SK "namespace#serviceAccount"
        string associationId "assoc-{uuid}"
        string clusterName "Cluster name"
        string namespace "K8s namespace"
        string serviceAccount "K8s service account"
        string roleArn "IAM role ARN"
        string createdAt "ISO 8601 timestamp"
    }

    CLUSTERS ||--o{ ASSOCIATIONS : "has"
```

## 8. Infrastructure Components

```mermaid
graph LR
    subgraph "API Gateway (REST API v1)"
        direction TB
        OPEN["/clusters/{name}/assets<br/>POST — No auth"]
        IAM_C["/clusters<br/>GET, POST — IAM auth"]
        IAM_CN["/clusters/{name}<br/>GET, DELETE — IAM auth"]
        IAM_J["/clusters/{name}/jwks<br/>PUT — IAM auth"]
        ASSOC["/clusters/{name}/pod-identity-associations<br/>ANY — SA token auth"]
    end

    subgraph "Lambda"
        FN["eks-dx-lambda<br/>Java 21 · SnapStart · 512MB"]
    end

    subgraph "DynamoDB"
        T1["eks-dx-clusters<br/>PAY_PER_REQUEST · PITR"]
        T2["eks-dx-associations<br/>PAY_PER_REQUEST · PITR"]
    end

    subgraph "CloudWatch"
        A1["Lambda Errors > 5/5min"]
        A2["Lambda Throttles > 1/5min"]
        A3["Lambda p99 > 5s"]
        A4["DynamoDB Throttles > 1/5min"]
        LOG["API Access Logs<br/>30-day retention"]
    end

    OPEN --> FN
    IAM_C --> FN
    IAM_CN --> FN
    IAM_J --> FN
    ASSOC --> FN
    FN --> T1
    FN --> T2
    FN -.-> A1
    FN -.-> A2
    FN -.-> A3
    T1 -.-> A4
```

## 9. CLI Command Tree

```mermaid
graph TD
    EKS_DX["eks-dx"]
    EKS_DX --> CONFIGURE["configure<br/>--endpoint --region"]
    EKS_DX --> CREATE["create"]
    EKS_DX --> DESCRIBE["describe"]
    EKS_DX --> LIST["list"]
    EKS_DX --> UPDATE["update"]
    EKS_DX --> DELETE["delete"]

    CREATE --> CC["cluster<br/>--name --region"]
    CREATE --> CA["pod-identity-association<br/>--cluster-name --namespace<br/>--service-account --role-arn"]

    DESCRIBE --> DC["cluster --name"]
    DESCRIBE --> DA["pod-identity-association<br/>--cluster-name --association-id"]

    LIST --> LC["clusters"]
    LIST --> LA["pod-identity-associations<br/>--cluster-name"]

    UPDATE --> UC["cluster --name --refresh-jwks"]

    DELETE --> DEC["cluster --name"]
    DELETE --> DEA["pod-identity-association<br/>--cluster-name --association-id"]
```

## 10. Deployment Topology

```mermaid
graph TB
    subgraph "Region: us-east-1 (AWS)"
        APIGW["API Gateway"]
        Lambda["Lambda + SnapStart"]
        DDB1["DynamoDB: clusters"]
        DDB2["DynamoDB: associations"]
    end

    subgraph "Cluster A (k3s on EC2)"
        A_Agent["Pod Identity Agent"]
        A_Proxy["eks-dx-auth-proxy"]
        A_Webhook["Webhook"]
        A_Pods["Application Pods"]
    end

    subgraph "Cluster B (microk8s on-prem)"
        B_Agent["Pod Identity Agent"]
        B_Proxy["eks-dx-auth-proxy"]
        B_Webhook["Webhook"]
        B_Pods["Application Pods"]
    end

    subgraph "Cluster C (EKS-D)"
        C_Agent["Pod Identity Agent"]
        C_Proxy["eks-dx-auth-proxy"]
        C_Webhook["Webhook"]
        C_Pods["Application Pods"]
    end

    A_Proxy --> APIGW
    B_Proxy --> APIGW
    C_Proxy --> APIGW
    A_Webhook --> APIGW
    B_Webhook --> APIGW
    C_Webhook --> APIGW
    APIGW --> Lambda
    Lambda --> DDB1
    Lambda --> DDB2
```
