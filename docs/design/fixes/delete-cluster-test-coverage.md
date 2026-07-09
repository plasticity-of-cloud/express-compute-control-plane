# Fix: Add Test Coverage for deleteCluster and deprovision Paths

## Problem

The `deleteCluster()` method in `TenantProvisioningService` has **zero unit test coverage**.
This led to a production issue where a managed cluster (`ecp-test`) was treated as self-managed
during deletion — only the DynamoDB records were removed while the EC2 instance, EIP, subnets,
security groups, and IAM roles were left as orphaned resources.

### Root Cause

The cluster record in `eks-d-xpress-clusters` was missing the `managed=true` field (likely
created by an older code path before the field was consistently written). The `deleteCluster()`
method defaults to self-managed when the field is absent:

```java
boolean managed = cluster.containsKey("managed") && "true".equals(cluster.get("managed").s());
```

This is correct defensive behavior, but the lack of test coverage meant:
1. No test verified the managed path calls `deprovision()`
2. No test verified the self-managed path only deregisters
3. No test caught the missing-field edge case
4. No test verified authorization enforcement

## Fix

Add comprehensive unit tests for:

| Scenario | Expected behavior |
|----------|-------------------|
| `deleteCluster` with `managed=true` | Calls `deprovision(tenantId)` |
| `deleteCluster` with `managed=false` | Only deletes DynamoDB records |
| `deleteCluster` with `managed` field missing | Treats as self-managed (deregister only) |
| `deleteCluster` by non-owner | Throws `SecurityException` |
| `deleteCluster` for non-existent cluster | Throws `IllegalArgumentException` |
| `deleteCluster` owner check skipped when ownerArn is null | Allows deletion |

## Impact

Test-only change. No production code modifications. Prevents regression if the
routing logic is ever refactored.
