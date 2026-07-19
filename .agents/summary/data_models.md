# Data Models

## DynamoDB Tables

### ecp-clusters

Stores registered cluster metadata and JWKS.

| Attribute | Type | Key | Description |
|-----------|------|-----|-------------|
| `clusterName` | S | PK | Unique cluster name |
| `ownerArn` | S | — | IAM ARN of registering user |
| `issuer` | S | — | OIDC issuer URL |
| `jwks` | S | — | JSON Web Key Set (cached) |
| `jwksLastUpdated` | N | — | Epoch timestamp of JWKS refresh |
| `managed` | BOOL | — | Whether cluster is managed (provisioned) |
| `createdAt` | S | — | ISO timestamp |

### ecp-workload-identities

Maps service accounts to IAM roles for credential exchange.

| Attribute | Type | Key | Description |
|-----------|------|-----|-------------|
| `PK` | S | Partition | `CLUSTER#<clusterName>` |
| `SK` | S | Sort | `<namespace>#<serviceAccount>` |
| `roleArn` | S | — | IAM role ARN to assume |
| `associationId` | S | — | UUID for API reference |
| `ownerArn` | S | — | IAM ARN of creator |
| `clusterName` | S | — | Denormalized cluster name |
| `namespace` | S | — | K8s namespace |
| `serviceAccount` | S | — | K8s service account name |
| `createdAt` | S | — | ISO timestamp |

### ecp-tenants

Tracks tenant provisioning state and resources.

| Attribute | Type | Key | Description |
|-----------|------|-----|-------------|
| `tenantId` | S | PK | Deterministic 8-char hex ID |
| `clusterName` | S | — | Associated cluster name |
| `ownerArn` | S | — | Provisioning user's ARN |
| `idcUserId` | S | — | IAM Identity Center user ID |
| `state` | S | — | Current state (PROVISIONING, READY, STOPPED, FAILED) |
| `progress` | N | — | Progress percentage (0–100) |
| `instanceId` | S | — | EC2 instance ID |
| `publicIp` | S | — | Public IP (EIP) |
| `arch` | S | — | Architecture (arm64, x86_64) |
| `ec2PricingModel` | S | — | spot or ondemand |
| `managed` | BOOL | — | Always true for provisioned tenants |
| `createdAt` | S | — | ISO timestamp |
| `updatedAt` | S | — | Last state change |

## Domain Records

### TokenClaims (shared model)

```java
record TokenClaims(
    String namespace,
    String serviceAccount,
    String podName,
    String podUid,
    String serviceAccountUid,
    Map<String, String> sessionTags,
    Instant expiration
)
```

### CallerIdentity (shared model)

```java
record CallerIdentity(
    String userArn,          // Full IAM ARN
    String idcUserId,        // Extracted IAM Identity Center email
    String sourceIp          // Client IP from API Gateway context
)
```

### TenantItem

```java
record TenantItem(
    String tenantId,
    String clusterName,
    String ownerArn,
    String idcUserId,
    String state,
    int progress,
    String instanceId,
    String publicIp,
    String arch,
    String ec2PricingModel,
    boolean managed,
    String createdAt,
    String updatedAt
)
```

### TenantProgress

```java
record TenantProgress(
    String tenantId,
    String state,
    int progress,
    String message
)
```

### ProvisionedResources (rollback tracker)

Tracks AWS resources created during provisioning for compensating rollback:
- Subnet ID
- Security group ID
- IAM role name / instance profile name
- EC2 instance ID
- EIP allocation ID
- SQS queue URLs (progress + interruption)
- EventBridge rule names
- DLM policy ID
- Secrets Manager secret ARNs

## SSM Parameter Schema

```
/express-compute/infra/launch-template/{arch}/{spot|ondemand}  → Launch template ID
/express-compute/infra/ami/{arch}/{k8s-version}                → AMI ID
/express-compute/infra/network/vpc-id                          → VPC ID
/express-compute/control-plane/api/endpoint                    → API Gateway URL
/express-compute/control-plane/quota/max-tenants-per-caller    → Quota limit
```

## Tenant ID Generation

Deterministic 8-character lowercase hex hash derived from `SHA-256(userId + createdAt)`. Extended variant (9 chars) used for some resource naming. Same inputs always produce the same ID.
