# Tenant Instance Lifecycle: Hibernate & Resume

## Overview

The tenant service manages EC2 instance lifecycle for cost optimization. Instances can be hibernated when idle and resumed on demand, preserving full in-memory state (EKS-D control plane, etcd, running pods).

## API

```
POST /tenants/{id}/hibernate   → 202 Accepted
POST /tenants/{id}/resume      → 202 Accepted
GET  /tenants/{id}             → includes "state": "running|hibernating|stopped|pending"
```

## How Hibernation Works

```
Running Instance (m6g.large, 8GB RAM)
    │
    ├─ POST /tenants/{id}/hibernate
    │   └─ ec2:StopInstances(hibernate=true)
    │       └─ RAM contents written to encrypted root EBS (30GB)
    │       └─ Instance enters "stopped" state
    │       └─ No compute charges (only EBS storage)
    │
    └─ POST /tenants/{id}/resume
        └─ ec2:StartInstances
            └─ RAM restored from EBS
            └─ Instance resumes exactly where it left off
            └─ etcd, kubelet, pods all resume without restart
```

## Triggers

### Automatic (spot reclaim)
- AWS hibernates the instance on spot capacity reclaim
- No application code needed — handled by EC2 + `ec2-hibinit-agent` (AL2023)
- Instance resumes automatically when capacity returns (spot `persistent` type)

### On-demand (tenant service API)
- Tenant idle detection → hibernate (saves compute cost)
- Tenant activity / API call → resume
- CLI: `ecp stop-tenant my-tenant` / `ecp resume-tenant my-tenant`

## Implementation

### TenantResource.java

```java
@POST
@Path("/{id}/hibernate")
public Response hibernate(@PathParam("id") String id) {
    provisioningService.hibernate(id);
    return Response.accepted().build();
}

@POST
@Path("/{id}/resume")
public Response resume(@PathParam("id") String id) {
    provisioningService.resume(id);
    return Response.accepted().build();
}
```

### TenantProvisioningService.java

```java
public void hibernate(String tenantId) {
    String instanceId = getInstanceId(tenantId);
    ec2.stopInstances(StopInstancesRequest.builder()
        .instanceIds(instanceId)
        .hibernate(true)
        .build());
    updateState(tenantId, "hibernating");
}

public void resume(String tenantId) {
    String instanceId = getInstanceId(tenantId);
    ec2.startInstances(StartInstancesRequest.builder()
        .instanceIds(instanceId)
        .build());
    updateState(tenantId, "resuming");
}
```

### State Machine

```
                 ┌──────────────────────────────────────┐
                 │                                      │
    provision    ▼         hibernate         resume     │
 ──────────► running ──────────────► stopped ──────────►┘
                │                       │
                │ terminate              │ terminate
                ▼                       ▼
            terminated              terminated
```

DynamoDB `ecp-tenants` table stores current state:

| tenantId | instanceId | state | arch | ec2PricingModel |
|----------|-----------|-------|------|-----------------|
| my-tenant | i-0abc123 | running | arm64 | spot |

## IAM Permissions Required

Add to CDK stack (`EcpStack.java`) for the tenant Lambda:

```java
tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
    .actions(List.of(
        "ec2:RunInstances", "ec2:TerminateInstances",
        "ec2:StopInstances", "ec2:StartInstances",
        "ec2:CreateKeyPair", "ec2:DeleteKeyPair",
        "ec2:DescribeInstances", "ec2:CreateTags"))
    .resources(List.of("*"))
    .build());
```

New actions: `ec2:StopInstances`, `ec2:StartInstances`

## Prerequisites (already met)

| Requirement | Status |
|-------------|--------|
| AL2023 AMI with `ec2-hibinit-agent` | ✅ pre-installed |
| Encrypted root EBS volume | ✅ configured in LT |
| Root volume ≥ RAM (30GB > 8GB) | ✅ |
| m6g.large supports hibernation | ✅ |
| Spot LT: `instance_interruption_behavior = "hibernate"` | ✅ in ecp-infra |
| Spot LT: `spot_instance_type = "persistent"` | ✅ enables auto-resume |

## Cost Impact

| State | Compute | EBS (root 30GB + etcd 10GB) | Monthly (us-east-1) |
|-------|---------|----------------------------|---------------------|
| Running (m6g.large spot) | ~$0.03/hr | ~$3.20/mo | ~$25/mo |
| Hibernated | $0 | ~$3.20/mo | ~$3.20/mo |

**Savings**: ~87% when hibernated vs running.

## Future: Auto-Hibernate

Optional idle detection (not in scope for v1):

```
EventBridge rule → every 15 min → check CloudWatch metric (API calls = 0 for 1hr)
    → invoke tenant Lambda → hibernate
```

Resume is always explicit: `ecp resume-tenant my-tenant` (CLI or API).
The auth-proxy runs inside the tenant cluster, so it cannot trigger resume when the instance is stopped.
