# Architecture

## System Overview

EKS-DX brings EKS Pod Identity (`AssumeRoleForPodIdentity`) to non-EKS Kubernetes clusters (EKS-D, k3s, microk8s) through a centralized serverless backend with DynamoDB storage and KMS-backed PKI.

```mermaid
graph TB
    subgraph "Tenant Cluster"
        Pod[Pod with SA Token]
        Agent[Pod Identity Agent]
        Proxy[eks-dx-auth-proxy]
        Webhook[Pod Identity Webhook]
        Karpenter[Karpenter Support]
    end

    subgraph "AWS Control Plane"
        APIGW[API Gateway]
        CredSvc[credential-service<br/>SnapStart Lambda]
        MgmtSvc[mgmt-service<br/>Lambda]
        TenantSvc[tenant-service<br/>Native Lambda]
        DDB[(DynamoDB)]
        STS[AWS STS]
        KMS[AWS KMS]
    end

    Pod -->|SA Token| Agent
    Agent -->|Forward| Proxy
    Proxy -->|1. TokenReview| K8sAPI[K8s API Server]
    Proxy -->|2. Forward token| APIGW
    APIGW --> CredSvc
    CredSvc -->|3. JWKS validate| DDB
    CredSvc -->|4. Lookup association| DDB
    CredSvc -->|5. AssumeRole| STS
    Webhook -->|Inject env+volume| Pod

    MgmtSvc --> DDB
    TenantSvc --> DDB
    TenantSvc --> KMS
```

## Architectural Layers

```mermaid
graph LR
    subgraph "Data Plane (hot path)"
        A[credential-service]
    end
    subgraph "Control Plane"
        B[mgmt-service]
        C[tenant-service]
    end
    subgraph "In-Cluster"
        D[auth-proxy]
        E[pod-identity-webhook]
        F[karpenter-support]
    end
    subgraph "Client"
        G[eks-dx CLI]
    end

    G -->|SigV4| B
    G -->|SigV4| C
    D -->|Open| A
```

## Design Principles

1. **Hot-path isolation**: Credential exchange (data plane) is separated from management operations. The credential-service uses SnapStart for cold-start latency optimization.

2. **Composable provisioning with compensating rollback**: Tenant provisioning is decomposed into discrete service calls (network, crypto, IAM, SQS, DLM, EC2). `ProvisionedResources` tracks created resources; on failure, `rollback()` cleans up in reverse order.

3. **KMS-backed PKI**: Tenant CA certificates are signed by a shared KMS asymmetric key. SA signing keys and JWKS are pre-registered in DynamoDB before EC2 launch — no post-boot registration needed.

4. **Dual-mode cluster lifecycle**: The unified `POST /clusters` endpoint handles both managed (full EC2 provisioning) and self-managed (BYOK register-only). Mode is inferred from request body.

5. **O(1) credential lookup**: DynamoDB uses `PK=CLUSTER#<name>` / `SK=<namespace>#<serviceAccount>` for instant GetItem during credential exchange.

6. **Per-user tenancy**: `CallerIdentityFilter` extracts `idcUserId` from IAM Identity Center session names. Quota enforcement is per-user.

## Authentication Model

| Endpoint | Auth Method | Details |
|----------|-------------|---------|
| `POST /clusters/{name}/assets` | None (API GW level) | Token validated by Lambda via JWKS |
| Management APIs | IAM SigV4 | Via API Gateway authorizer |
| Tenant-service Function URL | IAM SigV4 | Direct Lambda invocation |
| Pod SA tokens | JWT | Audience: `pods.eks.amazonaws.com` |
| In-cluster proxy → K8s | TokenReview | Fast-fail before Lambda call |

## Infrastructure Patterns

- **SSM Parameter Contract**: Infrastructure (CDK) writes parameters, Lambda reads at runtime. Decouples deployment timing.
- **Shared VPC**: All tenants share a VPC; each gets a dedicated subnet + security group.
- **Naming conventions**: Shared infra uses `eks-d-xpress-` prefix; per-tenant uses `eks-dx-tenant-` prefix (via `TenantNaming` class).
- **Platform tagging**: Shared resources tagged `Platform=eks-d-xpress`.
