# Interfaces and APIs

## REST APIs

### Credential Exchange (Data Plane)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/clusters/{name}/assets` | None (token in body) | Exchange SA token for AWS credentials |

**Request**: `AgentRequest { ClusterName, Token }`
**Response**: `AgentResponse { Subject, Association, AssumedRoleUser, Credentials }`

### Cluster Management (Control Plane — mgmt-service)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/clusters` | IAM SigV4 | Register a self-managed cluster |
| `GET` | `/clusters` | IAM SigV4 | List clusters (owned by caller) |
| `GET` | `/clusters/{name}` | IAM SigV4 | Describe cluster |
| `DELETE` | `/clusters/{name}` | IAM SigV4 | Deregister cluster |
| `POST` | `/clusters/{name}/refresh-jwks` | IAM SigV4 | Refresh JWKS cache |

### Association Management (Control Plane — mgmt-service)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/clusters/{name}/pod-identity-associations` | IAM SigV4 | Create association |
| `GET` | `/clusters/{name}/pod-identity-associations` | IAM SigV4 | List associations |
| `GET` | `/clusters/{name}/pod-identity-associations/{id}` | IAM SigV4 | Describe association |
| `DELETE` | `/clusters/{name}/pod-identity-associations/{id}` | IAM SigV4 | Delete association |

### Unified Cluster Lifecycle (tenant-service)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/clusters` | IAM SigV4 | Create cluster (managed or self-managed) |
| `GET` | `/clusters/{name}` | IAM SigV4 | Get cluster state |
| `DELETE` | `/clusters/{name}` | IAM SigV4 | Delete cluster |
| `POST` | `/clusters/{name}/stop` | IAM SigV4 | Hibernate cluster |
| `POST` | `/clusters/{name}/resume` | IAM SigV4 | Resume cluster |

### Tenant Provisioning (tenant-service — legacy)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/tenants` | IAM SigV4 | Create tenant |
| `GET` | `/tenants/{id}` | IAM SigV4 | Get tenant state |
| `DELETE` | `/tenants/{id}` | IAM SigV4 | Delete tenant |

### SSE Progress Stream (tenant-service Function URL)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/tenants/{id}/stream` | IAM SigV4 | Server-sent events progress |

## Kubernetes APIs (Webhooks)

### Pod Identity Webhook

- **Type**: Mutating Admission Webhook
- **Resource**: `pods` (CREATE)
- **Action**: Injects `AWS_CONTAINER_CREDENTIALS_FULL_URI`, `AWS_CONTAINER_AUTHORIZATION_TOKEN` env vars + projected SA token volume

### EC2NodeClass Webhook (Karpenter Support)

- **Type**: Mutating Admission Webhook
- **Resource**: `ec2nodeclasses.karpenter.sh` (CREATE, UPDATE)
- **Action**: Rewrites `amiFamily` to `Custom`, injects userData (MIME merge), adds cluster tags

## Internal Service Interfaces

### TokenValidationService (auth-proxy)

```java
TokenClaims validateToken(String token)
// Performs K8s TokenReview, returns claims (namespace, sa, pod, uid, sessionTags)
```

### JwksTokenValidationService (credential-service)

```java
TokenClaims validateToken(String clusterName, String token)
// Validates JWT against DynamoDB-cached JWKS, returns claims
```

### TenantProvisioningService

```java
String provision(String clusterName, TenantItem item)     // Full provisioning
void deprovision(String tenantId)                         // Full teardown
void rollback(ProvisionedResources resources)             // Compensating cleanup
TenantItem getState(String tenantId)                      // Current state
```

### TenantNaming (Constants API)

```java
static String roleName(String tenantId)
static String instanceProfileName(String tenantId)
static String securityGroupName(String tenantId)
static String keyPairName(String tenantId)
static String secretPath(String tenantId)
static String queueName(String tenantId)
static String progressQueueName(String tenantId)
static String eventRuleName(String tenantId)
static String dlmRoleName(String tenantId)
```

## CLI Commands

```
eks-dx create-cluster <name> [--wait] [--managed|--unmanaged]
eks-dx delete-cluster <name> [--wait]
eks-dx describe-cluster <name>
eks-dx list-clusters
eks-dx update-cluster <name> [--refresh-jwks]
eks-dx stop-cluster <name>
eks-dx resume-cluster <name>
eks-dx get-cluster-access <name>
eks-dx create-association --cluster <name> --namespace <ns> --service-account <sa> --role-arn <arn>
eks-dx delete-association --cluster <name> --id <id>
eks-dx describe-association --cluster <name> --id <id>
eks-dx list-associations --cluster <name> [--namespace <ns>]
eks-dx configure
```
