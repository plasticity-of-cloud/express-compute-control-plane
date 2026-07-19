# Security Hardening — SQS-Based Progress Reporting (Remove DynamoDB Direct Write)

## Status: Implemented

## Problem

The EC2 tenant instance currently has `dynamodb:UpdateItem` on the tenants table via its instance profile. The `progress.sh` script writes state, phase, and progress directly to DynamoDB:

```bash
aws dynamodb update-item --table-name "${ECP_TENANTS_TABLE}" \
  --key '{"tenantId":{"S":"'${TENANT_ID}'"}}' \
  --update-expression "SET #s = :s, phase = :p, progress = :n, updatedAt = :t" ...
```

This creates multiple security and reliability concerns:

1. **Trust boundary violation** — a compromised instance can write arbitrary state (claim `ready` prematurely, corrupt fields, write invalid transitions)
2. **No rate limiting** — instance can flood DynamoDB with unlimited UpdateItem calls
3. **No validation** — state transitions are not enforced (e.g., `ready → provisioning` should be impossible)
4. **Broad IAM surface** — instance profile needs table ARN and DynamoDB permissions
5. **No audit trail** — writes go directly to DynamoDB, no intermediate log of what the instance reported vs what was persisted

## Proposed Solution

Replace direct DynamoDB writes with a **per-tenant SQS FIFO queue**. The EC2 instance sends progress messages to its dedicated queue; tenant-service Lambda (already running, serving the SSE stream) polls the queue, validates before persisting, and deletes the queue once provisioning reaches a terminal state.

### Architecture

```
EC2 (progress.sh)
  │
  │ sqs:SendMessage (MessageGroupId = tenantId)
  │ MessageDeduplicationId = tenantId + progress (5-min dedup window)
  │
  └──→ SQS FIFO Queue (ecp-tenant-<tenantId>-progress.fifo)
         │                         ← per-tenant, ephemeral (~5-10 min lifetime)
         │ ReceiveMessage (polled by SSE Lambda during stream)
         │
         └──→ Tenant-service Lambda (SSE endpoint)
                │
                ├── Validate: state transition allowed?
                ├── Validate: progress only goes forward?
                ├── Validate: message schema correct?
                │
                ├── dynamodb:UpdateItem (if valid)
                │     └── Stream SSE event to client
                │
                └── On terminal state ("ready" | "failed"):
                      └── DeleteQueue (immediate cleanup)
```

### Why Per-Tenant Queues (Not Shared)

A shared FIFO queue has two correctness issues that per-tenant queues eliminate:

1. **Cross-tenant message stealing** — SQS FIFO does not support filtering `ReceiveMessage` by `MessageGroupId`. With a shared queue, Lambda A (streaming tenant-1) receives messages for tenant-2 and tenant-3, making them invisible for the visibility timeout period. This delays progress reporting for other tenants by up to 30 seconds per stolen message.

2. **IAM scoping impossible on shared queue** — `sqs:MessageGroupId` is not a valid IAM condition key. A scoped STS token for a shared queue cannot restrict which `MessageGroupId` the holder writes to. A compromised instance could spoof progress for other tenants.

Per-tenant queues solve both: IAM restricts `sqs:SendMessage` to the specific queue ARN, and each Lambda only receives messages for its own tenant.

**Queue count is not a concern** — AWS has no hard limit on number of SQS queues per account/region.

### Why SQS (Not EventBridge or SNS)

The SSE endpoint Lambda is already running (serving the long-lived streaming response). It needs to actively **poll** for progress updates during the stream. EventBridge and SNS are push-based — they invoke targets but cannot be polled by a running function.

| Requirement | SQS | EventBridge | SNS |
|-------------|-----|-------------|-----|
| Pollable from running Lambda | ✅ | ❌ | ❌ |
| Deduplication (prevent spam) | ✅ FIFO 5-min window | ❌ | ❌ |
| Per-tenant message ordering | ✅ MessageGroupId | ❌ | ❌ |
| Rate protection | ✅ 300 msg/s per group | Rules-based | ❌ |

### Protection Mechanisms

| Threat | Mitigation |
|--------|-----------|
| Instance spams queue | FIFO deduplication (same tenantId + progress value = dropped within 5 min) |
| Instance sends invalid state | Lambda validates state transitions before persisting |
| Instance sends progress backward | Lambda rejects if new progress ≤ current progress |
| Instance writes after completion | Queue deleted on terminal state — SendMessage returns error |
| Instance writes to other tenants | Per-tenant queue + IAM scoped to queue ARN — impossible |
| Credential abuse after provisioning | Queue deleted = hard revocation; permission targets non-existent resource |

### Queue Lifecycle

```
┌─ Provisioning Lambda ─────────────────────────────────────────────────────┐
│  1. Delete queue if exists (idempotent — handles retry/crash recovery)     │
│  2. CreateQueue: ecp-tenant-<tenantId>-progress.fifo                   │
│     - ContentBasedDeduplication: false (explicit dedup IDs)                │
│     - MessageRetentionPeriod: 3600 (1 hour)                               │
│     - VisibilityTimeout: 10                                               │
│     - Tag: createdAt=<ISO timestamp>                                      │
│     - Tag: ecp-tenant=<tenantId>                                       │
│  3. Generate scoped STS token (sqs:SendMessage on this queue ARN)         │
│  4. Store token in Secrets Manager                                        │
│  5. Launch EC2                                                            │
└───────────────────────────────────────────────────────────────────────────┘

┌─ SSE Lambda (streaming) ──────────────────────────────────────────────────┐
│  Phase 1: Poll EC2 DescribeInstances (unchanged)                          │
│  Phase 2: Poll SQS ReceiveMessage every 5s                                │
│           Validate → persist to DynamoDB → stream SSE event               │
│  Terminal: state == "ready" OR state == "failed"                           │
│           → DeleteQueue immediately                                       │
└───────────────────────────────────────────────────────────────────────────┘

┌─ Crash Recovery ──────────────────────────────────────────────────────────┐
│  If provisioning Lambda is re-invoked for the same tenant (retry after    │
│  crash), it deletes the existing queue first, then creates a fresh one.   │
│  This ensures no stale messages from a prior attempt are consumed.        │
└───────────────────────────────────────────────────────────────────────────┘

┌─ Defensive Cleanup (belt-and-suspenders) ─────────────────────────────────┐
│  Deprovision path: if queue still exists (SSE Lambda crashed before       │
│  reaching terminal state), delete it during tenant deprovision.           │
│  Optional: scheduled Lambda (hourly) deletes progress queues with         │
│  createdAt tag > 1 hour.                                                  │
└───────────────────────────────────────────────────────────────────────────┘
```

### Queue Naming

```
ecp-tenant-<tenantId>-progress.fifo
```

Follows existing `TenantNaming` convention. Add to `TenantNaming.java`:

```java
public static String progressQueueName(String tenantId) {
    return RESOURCE_PREFIX + tenantId + "-progress.fifo";
}
```

### Instance Profile Direct Access (No STS Token Needed)

The per-tenant instance profile role includes `sqs:SendMessage` scoped to the tenant's own progress queue ARN. No STS token ceremony is required — the queue's ephemeral nature is the revocation mechanism.

**Why no scoped STS token:**
- Per-tenant queue ARN is deterministic and can be referenced in IAM before the queue exists
- Once provisioning completes, the queue is deleted — permission becomes useless
- Instance cannot create queues (no `sqs:CreateQueue`), cannot write to other tenants' queues (ARN-scoped)
- Queue deletion is immediate, unconditional hard revocation — stronger than token expiry

**Instance boot (progress.sh):**

```bash
# Instance profile already has sqs:SendMessage on this tenant's queue — just send directly
aws sqs send-message --queue-url "$PROGRESS_QUEUE_URL" \
  --message-group-id "$TENANT_ID" \
  --message-deduplication-id "${TENANT_ID}-${progress}" \
  --message-body '{"tenantId":"'"$TENANT_ID"'","state":"'"$state"'","phase":"'"$phase"'","progress":'"$progress"'}'
```

**Security properties:**
- Instance profile scoped to `arn:aws:sqs:<region>:<account>:ecp-tenant-<tenantId>-progress.fifo`
- Cannot write to any other queue (ARN mismatch)
- Cannot create or delete queues (no `sqs:CreateQueue`/`sqs:DeleteQueue`)
- Queue deleted on provisioning completion — instant hard revocation
- No secrets to manage, no token expiry to worry about, no credential fetch at boot

### IAM Scoping

Instance profile (per-tenant role, created by `TenantIamService`):

```json
{
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "secretsmanager:GetSecretValue",
      "Resource": "arn:aws:secretsmanager:<region>:<account>:secret:ecp/tenant/<tenantId>/*"
    },
    {
      "Effect": "Allow",
      "Action": "sqs:SendMessage",
      "Resource": "arn:aws:sqs:<region>:<account>:ecp-tenant-<tenantId>-progress.fifo"
    }
  ]
}
```

The `sqs:SendMessage` permission targets a queue that only exists during provisioning (~5-10 min). Once the SSE Lambda deletes it, the permission is inert. No DynamoDB write access needed.

### Message Schema

```json
{
  "tenantId": "abc123",
  "state": "provisioning",
  "phase": "Installing VPC CNI",
  "progress": 55
}
```

- `MessageGroupId`: `abc123` (tenantId)
- `MessageDeduplicationId`: `abc123-55` (tenantId + progress — prevents duplicate progress at same %)

### SSE Lambda Polling Loop

Current (DynamoDB polling):
```java
while (streaming) {
    TenantItem item = dynamoDb.getItem(...);  // polls DynamoDB
    if (item.progress() > lastProgress) sendSseEvent(item);
    Thread.sleep(5000);
}
```

Proposed (SQS polling):
```java
String queueUrl = sqs.getQueueUrl(r -> r.queueName(TenantNaming.progressQueueName(tenantId))).queueUrl();
int lastProgress = 0;

while (streaming) {
    var messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
        .queueUrl(queueUrl)
        .maxNumberOfMessages(10)
        .waitTimeSeconds(5)  // long polling — replaces Thread.sleep
        .build()).messages();

    for (var msg : messages) {
        ProgressEvent event = parse(msg.body());
        if (validate(event, lastProgress)) {
            persistToDynamoDb(event);
            lastProgress = event.progress();
            emitSseEvent(event);
        }
        sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(msg.receiptHandle()));
    }

    if (lastState is terminal) {
        sqs.deleteQueue(r -> r.queueUrl(queueUrl));
        break;
    }
}
```

### Validation Rules

The SSE Lambda validates each message before persisting:

```java
boolean validate(ProgressEvent event, int currentProgress) {
    // 1. Schema: required fields present
    if (event.tenantId() == null || event.state() == null || event.progress() < 0) return false;

    // 2. Progress only goes forward
    if (event.progress() <= currentProgress) return false;

    // 3. Valid state transitions
    return switch (event.state()) {
        case "provisioning", "booting", "pulling-key",
             "kubeadm-init", "kubeadm-done", "registering" -> true;
        case "ready", "failed" -> true;  // terminal states
        default -> false;
    };
}
```

### Changes Required

| Component | Change |
|-----------|--------|
| `TenantNaming` | Add `progressQueueName(tenantId)` |
| `TenantProvisioningService` | Create per-tenant FIFO queue (delete-first for idempotency) before EC2 launch |
| `TenantIamService` | Remove `dynamodb:UpdateItem`; add `sqs:SendMessage` scoped to tenant's progress queue ARN |
| `TenantStreamResource` (SSE) | Replace DynamoDB GetItem polling with SQS ReceiveMessage; delete queue on terminal state |
| Deprovision path | Delete progress queue if still exists (defensive) |
| Rollback path | Delete progress queue in `ProvisionedResources` rollback |
| `express-compute` → `progress.sh` | Replace `aws dynamodb update-item` with `aws sqs send-message` using instance profile creds directly |

### Migration Path

1. Deploy CDK changes (progress-sender role, Lambda role update)
2. Deploy tenant-service Lambda (consumer: SQS polling + queue lifecycle)
3. Update instance profile IAM (keep DynamoDB temporarily for old AMIs)
4. Update `progress.sh` in Golden AMI (producer: SQS send)
5. New AMI build picks up the change; old instances still work during transition
6. Remove DynamoDB write permission from instance profile (once all active instances use new AMI)

## References

- `docs/roadmap/implemented/control-plane-managed-oidc-jwks.md` — related: instance profile scoping
- `docs/roadmap/security-hardening/ssm-only-access.md` — related: reducing instance attack surface
- `express-compute` project: `eks-d-setup/progress.sh` — current DynamoDB write implementation (to be migrated)
