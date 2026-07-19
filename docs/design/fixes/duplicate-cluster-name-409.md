# Fix: Duplicate Cluster Name Silently Overwrites Existing Record

## Problem

When `ecp create-cluster <name>` is called with a name that already exists in DynamoDB,
both managed and self-managed paths execute unconditional `PutItem` writes that silently
overwrite the existing cluster record (JWKS, issuer, tenantId). This causes:

- The original running cluster to lose credential-exchange capability (its JWKS is replaced).
- Managed mode: a second EC2 instance is launched under the same cluster name, spending
  real AWS resources before any conflict is detected.
- No error is returned to the caller; the duplicate is invisible.

## Root Cause

`preRegisterCluster()` and `registerSelfManagedCluster()` in `TenantProvisioningService`
both use plain `dynamoDb.putItem()` with no condition expression on the `ecp-clusters`
table. The `clusterName` field is the partition key and would uniquely identify a conflict,
but nothing checks for its existence before writing.

## Fix

Two complementary guards are applied:

### 1. Fail-fast upfront check (before any AWS resource creation)

A `clusterExists(clusterName)` helper performs a `GetItem` on `clustersTable` before
any provisioning work begins. If the cluster already exists, a `ClusterAlreadyExistsException`
is thrown immediately, preventing any AWS resources from being created.

This is critical for managed mode: without it, the stack creates a subnet, security group,
KMS-signed CA, SSH key pair, IAM role, SQS queue, DLM policy, and EC2 instance before
reaching the DynamoDB write — all of which then need to be rolled back.

### 2. Conditional PutItem (TOCTOU safety net)

Both `preRegisterCluster()` and `registerSelfManagedCluster()` add:
```
conditionExpression("attribute_not_exists(clusterName)")
```
to their `PutItemRequest`. This ensures atomicity: if two concurrent `create-cluster`
calls race for the same name, at most one can succeed. The losing call catches
`ConditionalCheckFailedException` and re-throws as `ClusterAlreadyExistsException`.

### 3. API layer: 409 instead of 500

`ClusterResource.createCluster()` (tenant-service) catches `ClusterAlreadyExistsException`
and returns HTTP 409 with error type `ResourceInUseException`:
```json
{
  "__type": "ResourceInUseException",
  "message": "Cluster 'my-cluster' already exists. To replace it, run: ecp delete-cluster my-cluster"
}
```

### 4. CLI UX

`EcpApiClient.postFunctionUrl()` already extracts the `message` field and prints it to
stderr before exiting. The message is written to be self-explanatory and actionable, so
no CLI-side change is needed beyond the consistent `Error: ` prefix added to all error
output from the client.

## Affected Files

| File | Change |
|------|--------|
| `ecp-tenant-service/.../exception/ClusterAlreadyExistsException.java` | New — custom unchecked exception |
| `ecp-tenant-service/.../service/TenantProvisioningService.java` | Upfront check + conditional PutItem |
| `ecp-tenant-service/.../resource/ClusterResource.java` | Catch 409, return `ResourceInUseException` |
| `ecp-cli/.../util/EcpApiClient.java` | Consistent `Error: ` prefix on all error messages |

## Test Coverage

Unit tests in `ClusterResourceTest.java` verify:
- Managed mode: duplicate name → 409 `ResourceInUseException`
- Self-managed mode: duplicate name → 409 `ResourceInUseException`
- Happy path: new name → 202 / 201 unaffected

## UX Before / After

**Before:**
```
# No output; second cluster silently created; first cluster broken
$ ecp create-cluster my-cluster
Created cluster "my-cluster" (tenant: abc123, managed)
```

**After:**
```
$ ecp create-cluster my-cluster
Error: Cluster 'my-cluster' already exists. To replace it, run: ecp delete-cluster my-cluster
```
Exit code: 1
