# Tenant Provisioning: Failure Handling & Compensation

## Design: TTL-based Deferred Cleanup with Compensation Log

When provisioning fails mid-way, we don't rollback immediately. Instead:
1. Mark tenant as `failed` with a 24h TTL
2. Log each completed step (compensation log)
3. Admin inspects via SSM within the TTL window
4. After TTL expires, cleanup Lambda reverses all completed steps

This gives **debuggability** (admin can SSM into a half-provisioned instance) AND **eventual consistency** (no orphaned resources after TTL).

## Failure Flow

```
TenantProvisioningService.provision()
    │
    ├─ Step 1: createTenantNetwork()     ✅ → log compensation entry
    ├─ Step 2: createSecrets()           ✅ → log compensation entry
    ├─ Step 3: createTenantRole()        ✅ → log compensation entry
    ├─ Step 4: createInterruptionQueue() ✅ → log compensation entry
    ├─ Step 5: createEventBridgeRules()  ✅ → log compensation entry
    ├─ Step 6: createEtcdBackupPolicy()  ✅ → log compensation entry
    ├─ Step 7: launchInstance()          ❌ FAILS
    │
    └─ On catch:
         • Write DynamoDB:
           state = "failed"
           error = "EC2 RunInstances: InsufficientCapacity..."
           ttl = now + 24h (epoch seconds)
           compensationLog = [{step, resourceType, resourceIds}...]
         • Do NOT delete anything
         • Return tenantId (caller sees state=failed via GET)
```

## DynamoDB Schema Extension

```
ecp-tenants table:
┌─────────────────────────────────────────────────────────────────┐
│ PK: tenantId                                                    │
├─────────────────────────────────────────────────────────────────┤
│ state: "failed"                                                 │
│ error: "EC2 RunInstances: InsufficientInstanceCapacity"         │
│ ttl: 1748390400 (epoch seconds, DynamoDB TTL attribute)         │
│ compensationLog: [                                              │
│   {"step": 1, "action": "deleteSubnets",                       │
│    "ids": ["subnet-aaa", "subnet-bbb"]},                        │
│   {"step": 2, "action": "deleteSecrets",                        │
│    "arns": ["arn:..signing-key", "arn:..ssh-key"]},             │
│   {"step": 3, "action": "deleteRole",                           │
│    "roleName": "ecp-tenant-foo-instance-role"},              │
│   {"step": 4, "action": "deleteQueue",                          │
│    "queueName": "ecp-foo"},                                  │
│   {"step": 5, "action": "deleteEventBridgeRules",               │
│    "ruleNames": ["ecp-foo-spot-...", ...]},                  │
│   {"step": 6, "action": "deleteDlmPolicy",                     │
│    "policyId": "policy-0abc123"}                                │
│ ]                                                               │
│ updatedAt: "2026-05-26T..."                                     │
└─────────────────────────────────────────────────────────────────┘
```

Enable DynamoDB TTL on the `ttl` attribute. DynamoDB automatically deletes the item after expiry (but we clean up AWS resources before that via the scheduled Lambda).

## Admin Inspection Window (24h)

During the TTL window, the admin can:

```bash
# 1. Check what failed
ecp describe-tenant my-tenant
# → state: failed, error: "...", ttl: 2026-05-27T01:00:00Z

# 2. SSM into the instance (if it launched but failed to bootstrap)
aws ssm start-session --target i-0abc123

# 3. Check user-data execution logs
aws ssm start-session --target i-0abc123
$ journalctl -u ecp-boot.service
$ cat /var/log/cloud-init-output.log

# 4. Inspect created resources
aws ec2 describe-subnets --filters "Name=tag:ecp-tenant,Values=my-tenant"
aws iam get-role --role-name ecp-tenant-my-tenant-instance-role

# 5. Manual fix and retry
ecp delete-tenant my-tenant    # explicit cleanup
ecp create-tenant my-tenant    # retry

# 6. Or just wait — auto-cleanup after 24h
```

## Scheduled Cleanup Lambda

An EventBridge scheduled rule triggers cleanup for expired failed tenants:

```
EventBridge: rate(1 hour)
    │
    └─► Cleanup Lambda (or same tenant-service Lambda, different handler)
         │
         ├─ Scan DynamoDB: state=failed AND ttl < now
         │
         └─ For each expired tenant:
              ├─ Read compensationLog
              ├─ Execute compensation in reverse order:
              │   6. deleteDlmPolicy(policyId)
              │   5. deleteEventBridgeRules(ruleNames)
              │   4. deleteQueue(queueName)
              │   3. deleteRole(roleName) — detach policies, delete profile first
              │   2. deleteSecrets(arns)
              │   1. deleteSubnets(ids) — delete SG first, then subnets
              │   0. terminateInstance(instanceId) — if it exists
              │
              ├─ Delete DynamoDB item (or let TTL handle it)
              └─ Log: "Cleaned up expired tenant: my-tenant"
```

## Compensation Actions

| Step | Resource | Compensation Action |
|------|----------|-------------------|
| 1 | Subnets + SG + RT associations | Delete SG, disassociate RT, delete subnets |
| 2 | Secrets Manager (signing key, SSH key) | DeleteSecret (force, no recovery) |
| 3 | IAM role + instance profile | Detach policies, remove from profile, delete profile, delete role |
| 4 | SQS queue | DeleteQueue |
| 5 | EventBridge rules | RemoveTargets, DeleteRule (×3) |
| 6 | DLM policy + DLM role | DeleteLifecyclePolicy, detach policy, delete role |
| 7 | EC2 instance + EIP | TerminateInstances, release EIP |

## Implementation Sketch

### Orchestrator (try/catch wrapper)

```java
public String provision(String tenantId, ...) {
    List<CompensationEntry> log = new ArrayList<>();
    String clusterName = "ecp-" + tenantId;

    try {
        // Step 1
        var network = networkService.createTenantNetwork(...);
        log.add(new CompensationEntry(1, "deleteNetwork",
            Map.of("subnetIds", List.of(network.publicSubnetId(), network.privateSubnetId()),
                   "sgId", network.securityGroupId())));

        // Step 2
        var secrets = createSecrets(tenantId);
        log.add(new CompensationEntry(2, "deleteSecrets",
            Map.of("arns", secrets.arns())));

        // Step 3
        var iam = iamService.createTenantRole(...);
        log.add(new CompensationEntry(3, "deleteRole",
            Map.of("roleName", iam.roleName())));

        // ... steps 4-7 ...

        // Success
        writeDynamoState(tenantId, instanceId, "provisioning", null, null, log);
        return tenantId;

    } catch (Exception e) {
        LOG.errorf("Provisioning failed at step %d for tenant %s: %s",
            log.size() + 1, tenantId, e.getMessage());
        long ttl = Instant.now().plus(Duration.ofHours(24)).getEpochSecond();
        writeDynamoState(tenantId, null, "failed", e.getMessage(), ttl, log);
        return tenantId;
    }
}
```

### CompensationEntry record

```java
public record CompensationEntry(
    int step,
    String action,
    Map<String, Object> resourceIds
) {}
```

### Cleanup handler

```java
public void cleanupExpiredTenants() {
    // Scan for state=failed AND ttl < now
    List<TenantItem> expired = scanExpiredFailedTenants();

    for (TenantItem tenant : expired) {
        List<CompensationEntry> log = tenant.compensationLog();
        // Reverse order
        for (int i = log.size() - 1; i >= 0; i--) {
            executeCompensation(log.get(i));
        }
        // Terminate instance if it exists
        if (tenant.instanceId() != null) {
            ec2.terminateInstances(...);
        }
        // Delete DynamoDB record
        deleteTenantRecord(tenant.tenantId());
    }
}
```

## CDK Changes Required

1. **DynamoDB TTL**: Enable on `ttl` attribute
```java
Table.Builder.create(this, "TenantsTable")
    .tableName("ecp-tenants")
    .partitionKey(...)
    .timeToLiveAttribute("ttl")  // ← add this
    .build();
```

2. **EventBridge scheduled rule**: Trigger cleanup every hour
```java
Rule.Builder.create(this, "TenantCleanupSchedule")
    .schedule(Schedule.rate(Duration.hours(1)))
    .targets(List.of(new LambdaFunction(tenantFn, LambdaFunctionProps.builder()
        .event(RuleTargetInput.fromObject(Map.of("action", "cleanup")))
        .build())))
    .build();
```

3. **Lambda IAM**: tenant-service already has permissions to delete all these resources (it creates them).

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `ecp.tenant.failure-ttl-hours` | 24 | Hours before failed tenants are auto-cleaned |
| `ecp.tenant.cleanup-enabled` | true | Enable/disable scheduled cleanup |

## Edge Cases

| Scenario | Handling |
|----------|----------|
| Cleanup Lambda fails mid-compensation | Idempotent — re-runs next hour, skips already-deleted resources |
| Admin deletes tenant manually before TTL | `deprovision()` reads compensationLog, cleans up, deletes record |
| Instance launched but never bootstrapped | SSM reachable (agent is in AMI), admin can inspect |
| Instance never launched (capacity error) | No instance to inspect, but subnets/SG/IAM visible in console |
| DynamoDB TTL deletes record before cleanup | Resources orphaned — cleanup Lambda should also scan by tag as fallback |

## Fallback: Tag-based Orphan Detection

As a safety net, run a weekly scan for resources tagged `ecp-tenant=*` that have no matching DynamoDB record:

```bash
aws ec2 describe-instances --filters "Name=tag:ecp-tenant,Values=*" \
  | cross-reference with DynamoDB
  | terminate orphans older than 48h
```

This catches any resources that slip through the primary cleanup path.
