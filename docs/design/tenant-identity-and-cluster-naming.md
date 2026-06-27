# Tenant Identity and Cluster Naming

## Status

Proposed — supersedes naming section in `docs/roadmap/multi-tenancy-handling.md`

## Overview

Two distinct identifiers exist in the system:

| Identifier | Purpose | Source | Example |
|------------|---------|--------|---------|
| **Tenant ID** | AWS resource naming, SSH key path, internal key | System-derived hash | `a7f3b2c1` |
| **Cluster name** | Workload-visible identifier, pod-identity lookup key | User-provided, validated | `my-dev-cluster` |

The cluster name is **not globally unique** — the same name (e.g. `ci-pipeline`) can exist in multiple regions for different tenants. The tenant ID is globally unique and is the only stable key for AWS resources and local paths.

## Tenant ID

### Derivation

The tenant ID is a short hash derived from the IAM Identity Center (IdC) user ID of the requestor, similar to git commit short hashes:

```
tenantId = HEX(SHA256(idcUserId + ":" + createdAt))[:8]
```

- `idcUserId`: the Identity Center unique user ID (e.g., `d-1234567890/user@example.com` or the IdC `UserId` GUID)
- `createdAt`: ISO-8601 UTC timestamp at tenant creation time
- 8 hex characters = 32 bits of entropy, ~4 billion unique values

Properties:
- **Not user-chosen** — eliminates naming conflicts and resource name injection
- **Collision-resistant** — timestamp component guarantees uniqueness for same user across recreations
- **Short** — fits within IAM role name constraints with room to spare
- **Opaque** — does not leak identity information in resource names
- **Stable** — never changes after creation

### Collision Handling

On the rare collision (DynamoDB `attribute_not_exists` condition fails), retry with a counter suffix:

```java
String base = hex(sha256(idcUserId + ":" + createdAt));
String tenantId = base.substring(0, 8);  // first attempt
// on ConditionalCheckFailedException:
tenantId = base.substring(0, 8) + base.charAt(8);  // extend to 9 chars
```

## Cluster Name

### Validation Rules (EKS-compatible)

The cluster name is the user-facing identifier for the virtual cluster. It follows the same strict rules as EKS:

```
Pattern: ^[a-zA-Z][a-zA-Z0-9-]{0,99}$
```

- Starts with a letter
- Contains only alphanumeric characters and hyphens
- Maximum 100 characters
- Case-sensitive

### Scope

The cluster name is **scoped to a region**. The same name can be reused across regions (e.g. a CI/CD pipeline creating `ci-pipeline` in `us-east-1` and `eu-west-1`). Uniqueness is not enforced globally — only within the `eks-dx-clusters` DynamoDB table in each deployment region.

### Registration

The cluster name is what gets registered in `eks-dx-clusters` (for pod identity associations) and is visible to workloads via the credential exchange flow. It serves the same purpose as an EKS cluster name — it's how pods and operators reference the cluster.

**For EKS-D-Xpress tenants (current implementation):** the first-boot script calls `eks-dx register-cluster` after `kubeadm init` completes, reading the JWKS from the live cluster API. This is reactive — the cluster must be publicly reachable before it can be registered.

**For standalone clusters** (k3s, microk8s, EKS-D self-managed, etc.): a tenant record is still created, but with `managed: false`. Users call `register-cluster` manually after their cluster is up.

```bash
eks-dx create-tenant --cluster-name my-k3s --unmanaged
# → Created tenant a7f3b2c1 (cluster: my-k3s, unmanaged)

eks-dx register-cluster my-k3s \
  --issuer https://my-k3s-host \
  --jwks-file /path/to/jwks.json
```

### Registration Tradeoff (current vs. planned)

| | Current (this branch) | Planned (`feature/control-plane-managed-oidc`) |
|---|---|---|
| Managed tenant registration | First-boot script calls `register-cluster` after cluster is up | `tenant-service` pre-registers before EC2 boots (KMS-generated SA key pair) |
| Unmanaged tenant registration | User calls `register-cluster` after cluster is up | Same |
| Race condition risk | Yes — network hiccup at boot causes silent failure | Eliminated |
| `register-cluster` CLI needed for managed path | Yes (interim) | No |

See `docs/roadmap/security-hardening/control-plane-managed-oidc-jwks.md` for the full pre-registration design.

### Relationship to Tenant

```
┌─────────────────────────────────────────────────┐
│ DynamoDB: eks-d-xpress-tenants                   │
├─────────────────────────────────────────────────┤
│ PK: tenantId = "a7f3b2c1"                       │
│ clusterName = "my-dev-cluster"                  │
│ idcUserId = "d-906714.../user@example.com"      │
│ ownerArn = "arn:aws:iam::...:role/..."          │
│ createdAt = "2026-06-26T10:00:00Z"              │
│ ...                                             │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│ DynamoDB: eks-dx-clusters                        │
├─────────────────────────────────────────────────┤
│ PK: clusterName = "my-dev-cluster"              │
│ jwks = "..."                                    │
│ issuer = "https://..."                          │
│ ...                                             │
└─────────────────────────────────────────────────┘
```

A single tenant owns one virtual cluster. The cluster name is user-chosen and validated; the tenant ID is system-derived and opaque.

## DynamoDB Schema

**Table: `eks-d-xpress-tenants`** (PK: `tenantId`)

| Attribute | Type | Notes |
|---|---|---|
| `tenantId` | String (PK) | System-derived 8-char hex hash |
| `clusterName` | String | User-provided, validated |
| `managed` | Boolean | `true` = EKS-D-Xpress provisioned EC2; `false` = standalone cluster |
| `idcUserId` | String | Identity Center user ID (audit trail, input to hash) |
| `ownerArn` | String | Caller IAM ARN |
| `createdAt` | String | ISO-8601, immutable |
| `updatedAt` | String | ISO-8601, updated on state change |
| `state` | String | `provisioning`, `ready`, `failed`, `stopped` (null for unmanaged) |
| `phase` | String | Human-readable progress step (managed only) |
| `progress` | Number | 0–100 (managed only) |
| `instanceId` | String | EC2 instance ID (managed only) |
| `publicIp` | String | EIP address (managed only) |
| `eipAllocationId` | String | EIP tracking (managed only) |
| `sshKeySecretArn` | String | Secrets Manager ARN (managed only) |
| `ec2PricingModel` | String | `spot` or `ondemand` (managed only) |
| `error` | String | Set on provisioning failure (managed only) |

**GSI: `clusterName-index`** (PK: `clusterName`) — enables `delete-tenant` / `get-tenant` by cluster name as a convenience lookup.



All AWS resources use the **tenant ID** (not the cluster name) to stay within IAM's 64-char limit and avoid user-input in resource ARNs.

### Naming Convention

```
eks-dx-t-{tenantId}-{suffix}
```

Prefix `eks-dx-t-` (8 chars) + tenant ID (8 chars) + `-` + suffix = 17 chars overhead.

### Resource Table

| Resource | Current | Proposed | Chars |
|----------|---------|----------|-------|
| Instance role | `eks-d-xpress-tenant-karolpiatek-us-east-1-instance-role` | `eks-dx-t-a7f3b2c1-ir` | 21 |
| DLM role | `eks-d-xpress-tenant-karolpiatek-us-east-1-dlm` | `eks-dx-t-a7f3b2c1-dlm` | 22 |
| Key pair | `eks-d-xpress-tenant-karolpiatek` | `eks-dx-t-a7f3b2c1-key` | 22 |
| SQS queue | `eks-d-xpress-karolpiatek` | `eks-dx-t-a7f3b2c1-q` | 20 |
| Security group | `karolpiatek-eks-d-xpress` | `eks-dx-t-a7f3b2c1-sg` | 21 |
| Secrets | `eks-d-xpress/tenant/karolpiatek/ssh-key` | `eks-dx/t/a7f3b2c1/ssh-key` | 26 |
| Tag value | `eks-d-xpress-tenant: karolpiatek` | `eks-dx-tenant: a7f3b2c1` | — |

### Benefits

- No region in role name — IAM roles are global, region was redundant
- Shortened suffixes (`-ir` instead of `-instance-role`)
- Consistent `eks-dx-t-` prefix for IAM policy scoping: `arn:aws:iam::*:role/eks-dx-t-*`
- Maximum resource name length: ~25 chars (well within all AWS limits)

## Local CLI Paths

The CLI stores the SSH private key locally at:

```
~/.eks-d-xpress/tenants/{region}/{tenantId}.pem
```

Using `tenantId` (not `clusterName`) because the same cluster name can exist in multiple regions — keying by `clusterName` would cause path collisions for users running, e.g., a `ci-pipeline` cluster in both `us-east-1` and `eu-west-1`.

## First Boot Script

The boot script receives both `TENANT_ID` and `CLUSTER_NAME` via `/opt/eks-d/cluster.env` (written by `TenantEc2Service` at instance launch). Key usage:

| Variable | Used for |
|----------|----------|
| `TENANT_ID` | (informational only in boot script — AWS resources already named before boot) |
| `CLUSTER_NAME` | `eks-dx register-cluster` call, `EKS_CLUSTER_NAME` Helm value, `EKS_DX_API_URL` path |

```bash
# Boot script — register cluster using CLUSTER_NAME
eks-dx register-cluster "${CLUSTER_NAME}" \
  --issuer "https://${PUBLIC_IP}" \
  --jwks-file /tmp/jwks.json

helm install eks-dx-auth-proxy ... \
  --set app.envs.EKS_CLUSTER_NAME="${CLUSTER_NAME}"

helm install eks-dx-pod-identity-webhook ... \
  --set app.envs.EKS_CLUSTER_NAME="${CLUSTER_NAME}"
```

## API Changes

### Create Tenant Request

**Managed (EKS-D-Xpress provisioned):**
```json
POST /tenants
{
  "clusterName": "my-dev-cluster",
  "arch": "arm64",
  "ec2PricingModel": "spot",
  "k8sVersion": "1.35"
}
```

**Unmanaged (standalone cluster):**
```json
POST /tenants
{
  "clusterName": "my-k3s",
  "managed": false
}
```

Response (both):
```json
{
  "tenantId": "a7f3b2c1",
  "clusterName": "my-dev-cluster",
  "managed": true
}
```

### CLI

```bash
# Managed (provisions EC2 + EKS-D):
eks-dx create-tenant --cluster-name my-dev-cluster
# → Created tenant a7f3b2c1 (cluster: my-dev-cluster, managed)

# Unmanaged (standalone — k3s, microk8s, self-managed EKS-D):
eks-dx create-tenant --cluster-name my-k3s --unmanaged
# → Created tenant b9e1f4a2 (cluster: my-k3s, unmanaged)
# Then separately:
eks-dx register-cluster my-k3s --issuer https://... --jwks-file jwks.json

# Delete/get always by tenantId (unambiguous across regions):
eks-dx delete-tenant a7f3b2c1
eks-dx get-tenant a7f3b2c1
```

## Migration

No migration needed — no existing tenants. Implement directly with new naming convention.

## Implementation Scope (this branch)

What is implemented here:

1. **`TenantItem`** — add `clusterName`, `managed`, `idcUserId`, `createdAt` fields
2. **`TenantProvisioningService`** — generate `tenantId` server-side (SHA256 hash); accept `clusterName` separately; store `managed: true`
3. **`TenantResource`** — remove `tenantId` from request body; add `clusterName`, `managed` fields; create unmanaged tenant path
4. **`TenantEc2Service.userDataScript`** — resource names use `eks-dx-t-{tenantId}-{suffix}`; Helm + register-cluster use `CLUSTER_NAME`
5. **`TenantIamService` / `TenantNetworkService` / `TenantDlmService`** — rename resources to `eks-dx-t-{tenantId}-{suffix}`
6. **`CreateTenantCommand`** — replace positional `tenantId` with `--cluster-name`; add `--unmanaged` flag
7. **CDK** — add `clusterName-index` GSI to tenants table
8. **`docs/design/tenant/first-boot-script.md`** — update to use `CLUSTER_NAME` for register-cluster and Helm values

What is **not** implemented here (deferred to `feature/control-plane-managed-oidc`):
- Pre-registration of cluster JWKS before EC2 boots
- KMS-backed SA key generation in tenant-service
- Elimination of `register-cluster` from the managed boot path

## Validation Implementation

```java
private static final Pattern CLUSTER_NAME_PATTERN =
    Pattern.compile("^[a-zA-Z][a-zA-Z0-9-]{0,99}$");

private void validateClusterName(String name) {
    if (name == null || !CLUSTER_NAME_PATTERN.matcher(name).matches())
        throw new IllegalArgumentException(
            "clusterName must start with a letter, contain only [a-zA-Z0-9-], max 100 chars");
}
```

## Decisions

1. **`tenantId` is system-derived** — never user-provided, eliminates naming collisions and injection risks
2. **Every cluster has a tenant record** — managed and unmanaged; `managed: false` for standalone clusters (k3s, microk8s, self-managed EKS-D)
3. **`clusterName` is region-scoped, not globally unique** — same name allowed in different regions
4. **AWS resources and SSH key paths use `tenantId`** — stable, unique, short, no user input
5. **Boot script uses `CLUSTER_NAME`** for register-cluster and in-cluster components — workloads reference the cluster by name
6. **CLI `delete-tenant` / `get-tenant` take `tenantId`** — unambiguous across regions
7. **GSI on `clusterName`** — convenience lookup; secondary to `tenantId`
8. **8-char hex hash** — 32 bits of entropy, ~4 billion unique values
9. **No migration needed** — no existing tenants; implement directly with new naming
