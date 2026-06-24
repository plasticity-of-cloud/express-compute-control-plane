# Tenant Provisioning Design

Automated provisioning of a kubeadm-based Kubernetes cluster per tenant, integrated with EKS-DX Pod Identity.

## Overview

A Lambda function provisions a tenant cluster by:
1. Generating per-tenant credentials (SSH key pair + SA signing key)
2. Launching an EC2 instance via Launch Template
3. The instance self-registers with mgmt-service using a pre-provisioned signing key

Initial infrastructure (Launch Template, IAM instance profile, VPC, Secrets Manager KMS key) is set up once via Terraform.

Progress is streamed in real time via a dedicated Lambda Function URL (SSE, `RESPONSE_STREAM` mode). The `eks-dx` CLI and Amplify UI both consume this stream.

## Architecture

```
POST /tenants  (API Gateway → Lambda)
  Provisioning Lambda
    │
    ├─ 1. Generate RSA-2048 key pair (SA signing key)
    │      └─ private key → Secrets Manager: eks-dx/tenant/{tenantId}/signing-key
    │
    ├─ 2. ec2:CreateKeyPair("eks-dx-tenant-{tenantId}")
    │      └─ private key PEM → Secrets Manager: eks-dx/tenant/{tenantId}/ssh-key
    │
    ├─ 3. iam:CreateRole("eks-dx-tenant-{tenantId}-instance-role")
    │      └─ inline policy: GetSecretValue on eks-dx/tenant/{tenantId}/*
    │                         execute-api:Invoke on POST /clusters (mgmt-service)
    │                         dynamodb:UpdateItem on eks-dx-tenants table
    │
    ├─ 4. ec2:RunInstances(LaunchTemplate, KeyName, IamInstanceProfile)
    │
    ├─ 5. DynamoDB.put({ tenantId, instanceId, state: "provisioning", progress: 0 })
    └─ 6. → 202 { tenantId }

  EC2 Instance (user data — kubeadm bootstrap):
    │
    ├─ 1. DynamoDB.update({ state: "booting",       progress: 10 })
    ├─ 2. Pull signing key from Secrets Manager
    │      DynamoDB.update({ state: "pulling-key",  progress: 20 })
    ├─ 3. Write sa.key + sa.pub to /etc/kubernetes/pki/
    │      DynamoDB.update({ state: "kubeadm-init", progress: 40 })
    ├─ 4. kubeadm init --service-account-signing-key-file sa.key
    │                   --service-account-issuer https://{publicIp}
    │      DynamoDB.update({ state: "kubeadm-done", progress: 70 })
    ├─ 5. Derive public JWKS from sa.pub
    │      DynamoDB.update({ state: "registering",  progress: 85 })
    └─ 6. eks-dx register-cluster {tenantId} --issuer ... --jwks-file ...
           DynamoDB.update({ state: "ready", progress: 100, publicIp })

GET /tenants/{id}  (API Gateway → Lambda)
  → { tenantId, state, phase, progress, publicIp, sshKeySecretArn, updatedAt, error }

GET /tenants/{id}/stream  (Lambda Function URL — RESPONSE_STREAM)
  → SSE stream, polls DynamoDB every 5s, closes on state == "ready" | "failed"
```

## DynamoDB State Machine

The `eks-dx-tenants` table tracks provisioning state. The EC2 instance writes progress directly via its instance profile role.

| `state` | `progress` | Description |
|---|---|---|
| `provisioning` | 0 | Lambda launched EC2, waiting for first boot |
| `booting` | 10 | Instance first boot, user data started |
| `pulling-key` | 20 | Fetching SA signing key from Secrets Manager |
| `kubeadm-init` | 40 | `kubeadm init` running |
| `kubeadm-done` | 70 | `kubeadm init` completed |
| `registering` | 85 | Calling `POST /clusters` on mgmt-service |
| `ready` | 100 | Cluster registered, pod identity live |
| `failed` | — | Error stored in `error` field |

### DynamoDB item schema

```json
{
  "tenantId":        "acme-staging",
  "instanceId":      "i-0abc1234567890",
  "state":           "kubeadm-init",
  "phase":           "kubeadm init running",
  "progress":        40,
  "publicIp":        "54.12.34.56",
  "sshKeySecretArn": "arn:aws:secretsmanager:...:secret:eks-dx/tenant/acme-staging/ssh-key",
  "updatedAt":       "2026-05-21T13:00:00Z",
  "error":           null
}
```

## Progress Streaming (SSE)

### Why Lambda Function URL, not API Gateway

API Gateway has a hard 29-second timeout. Tenant provisioning takes 3–8 minutes. The SSE streaming endpoint must bypass API Gateway entirely using a **Lambda Function URL** with `InvokeMode: RESPONSE_STREAM`.

The provisioning `POST /tenants` and `GET /tenants/{id}` remain on API Gateway (fast, <5s). Only the streaming endpoint uses a Function URL.

### SSE endpoint behaviour

`GET https://<stream-function-url>/tenants/{id}/stream`

- Polls DynamoDB every **5 seconds**
- Emits one SSE event per poll (even if state unchanged — keeps connection alive)
- Closes stream when `state == "ready"` or `state == "failed"`
- Lambda timeout: 15 minutes (well above worst-case provisioning time)

SSE event format:
```
data: {"state":"kubeadm-init","phase":"kubeadm init running","progress":40,"elapsed":142}

data: {"state":"ready","phase":"Cluster registered","progress":100,"publicIp":"54.12.34.56","elapsed":312}
```

### Quarkus implementation

```java
@GET
@Path("/tenants/{id}/stream")
@Produces(MediaType.SERVER_SENT_EVENTS)
@RestStreamElementType(MediaType.APPLICATION_JSON)
public Multi<TenantProgress> streamProgress(@PathParam("id") String id) {
    return Multi.createFrom().ticks().every(Duration.ofSeconds(5))
        .map(tick -> tenantService.getProgress(id))
        .takeUntil(p -> p.state().equals("ready") || p.state().equals("failed"));
}
```

`TenantProgress` is a record:
```java
public record TenantProgress(
    String state, String phase, int progress,
    String publicIp, long elapsed, String error) {}
```

### CDK stack addition

```yaml
TenantStreamFunction:
  Type: AWS::Serverless::Function
  Properties:
    Handler: io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest
    Runtime: provided.al2023
    MemorySize: 128
    Timeout: 900
    FunctionUrlConfig:
      AuthType: AWS_IAM
      InvokeMode: RESPONSE_STREAM
    Policies:
      - DynamoDBReadPolicy:
          TableName: !Ref EksDxTenantsTable
```

## Cost

The streaming Lambda runs as a GraalVM native image (`provided.al2023`), which uses ~128MB vs ~256–512MB for JVM.

| Scale | GB-seconds/month | Duration cost | Request cost | Total |
|---|---|---|---|---|
| 100 provisions/month | 100 × 22.5 = 2,250 | $0 (free tier) | ~$0.00 | **$0.00** |
| 1,000 provisions/month | 22,500 | $0 (free tier) | ~$0.00 | **$0.00** |
| 10,000 provisions/month | 225,000 | $0 (free tier) | ~$0.00 | **$0.00** |
| 17,778+ provisions/month | >400,000 | ~$0.83 overage | ~$0.002 | **~$0.83** |

Free tier: 400,000 GB-seconds/month, 1M requests/month. At 128MB × 180s = 22.5 GB-seconds per invocation, the free tier covers ~17,777 provisions/month. CI/CD workloads will not exit the free tier.

## CLI Integration (`eks-dx create-tenant --wait`)

The `eks-dx` CLI (PicoCLI) connects to the SSE stream endpoint and renders progress in the terminal.

```
$ eks-dx create-tenant acme-staging --wait

Provisioning tenant acme-staging...
  ✓ EC2 instance launched (i-0abc1234567890)
  ✓ Instance booting
  ✓ Signing key fetched from Secrets Manager
  ⠸ kubeadm init running... [3m 12s]
  ○ Registering cluster with mgmt-service
  ○ Ready

Tenant ready. Public IP: 54.12.34.56
SSH key: aws secretsmanager get-secret-value --secret-id eks-dx/tenant/acme-staging/ssh-key
```

With `--output json`, the spinner is suppressed and the final state is printed as JSON on completion — suitable for CI/CD script consumption.

### PicoCLI command sketch

```java
@Command(name = "tenant", subcommands = { CreateTenantCommand.class, ... })
public class TenantCommand implements Runnable { ... }

@Command(name = "create")
public class CreateTenantCommand implements Runnable {

    @Parameters(index = "0") String tenantId;
    @Option(names = "--wait")  boolean wait;
    @Option(names = "--output", defaultValue = "text") String output;

    @Override
    public void run() {
        String id = apiClient.createTenant(tenantId);  // POST /tenants → 202
        if (!wait) { out.println(id); return; }

        try (var stream = apiClient.streamProgress(id)) {  // SSE Function URL
            stream.forEach(event -> {
                if ("text".equals(output)) renderPhase(event);
                if ("ready".equals(event.state()) || "failed".equals(event.state())) {
                    if ("json".equals(output)) out.println(toJson(event));
                }
            });
        }
    }
}
```

`apiClient.streamProgress()` opens an HTTP connection to the Lambda Function URL, reads the SSE stream, and yields `TenantProgress` events. Auth is SigV4 (same as all other management endpoints).

## CI/CD Integration

CI/CD pipelines use `eks-dx create-tenant --wait --output json`. The CLI handles all polling internally — no custom loop needed in pipeline YAML.

```yaml
# GitHub Actions
- uses: aws-actions/configure-aws-credentials@v4
  with:
    role-to-assume: arn:aws:iam::ACCOUNT:role/ci-tenant-provisioner
    aws-region: us-east-1

- name: Provision tenant
  run: |
    RESULT=$(eks-dx create-tenant acme-${{ github.run_id }} --wait --output json)
    echo "PUBLIC_IP=$(echo $RESULT | jq -r .publicIp)" >> $GITHUB_ENV
```

The IAM role `ci-tenant-provisioner` requires only:
```json
[
  { "Action": "execute-api:Invoke",
    "Resource": "arn:aws:execute-api:*:*:*/*/POST/tenants" },
  { "Action": "lambda:InvokeFunctionUrl",
    "Resource": "arn:aws:lambda:*:*:function:TenantStreamFunction" }
]
```

OIDC federation (no long-lived credentials) is the recommended auth method for GitHub Actions, GitLab CI, and similar platforms.

## Amplify UI Integration

The Amplify UI connects to the same SSE endpoint using `EventSource`. Auth is AWS SigV4 via Amplify's built-in `fetchAuthSession`.

```typescript
// Amplify Gen 2 + React
const streamTenantProgress = (tenantId: string, onUpdate: (p: TenantProgress) => void) => {
  // EventSource doesn't support custom headers; use fetch with ReadableStream for SigV4
  fetchWithSigV4(`${STREAM_FUNCTION_URL}/tenants/${tenantId}/stream`)
    .then(res => readSseStream(res.body!, onUpdate));
};
```

Key metrics to display: progress bar (0–100%), current phase label, elapsed time, public IP on completion.

## Key Design Decisions

### Why Lambda, not ECS Fargate

The provisioning endpoint (`POST /tenants`) executes 5–6 sequential AWS API calls and returns 202 in under 5 seconds. Lambda is the correct fit:

- No always-on cost (~$30–50/month for a minimal Fargate task vs ~$0 for Lambda at CI/CD scale)
- No infrastructure to manage (task definitions, ALB, scaling policy)
- Consistent with the existing mgmt-service architecture
- SnapStart available if JVM mode is used; native image eliminates cold start entirely

ECS Fargate would only be warranted for persistent connections (gRPC streaming) or operations exceeding Lambda's 15-minute timeout — neither applies here.

### Why REST, not gRPC

REST is already implemented. gRPC would require proto schema management, a different auth story (mTLS or metadata headers instead of SigV4), and client library requirements in the CLI and Amplify UI — with no benefit for low-frequency, simple request/response provisioning calls.

### Why the signing key must persist in Secrets Manager

`JwksTokenValidationService` verifies every pod SA token against the **public JWKS stored in DynamoDB**. That JWKS is derived from the private signing key. kubeadm writes `sa.key` to `/etc/kubernetes/pki/sa.key` and uses it to sign all SA tokens.

If the instance reboots, the key is already on the EBS volume (`/etc/kubernetes/pki/sa.key`). Secrets Manager is the source of truth for disaster recovery and re-provisioning. Delete the secret only when deprovisioning the tenant.

### kubeadm SA signing key placement

kubeadm expects the SA signing key at `/etc/kubernetes/pki/sa.key` (private) and `/etc/kubernetes/pki/sa.pub` (public). The user data script must write these **before** running `kubeadm init`, otherwise kubeadm generates its own key and the pre-registered JWKS won't match.

```bash
# user data excerpt
SECRET=$(aws secretsmanager get-secret-value \
  --secret-id eks-dx/tenant/${TENANT_ID}/signing-key \
  --query SecretString --output text)

echo "$SECRET" > /etc/kubernetes/pki/sa.key
openssl rsa -in /etc/kubernetes/pki/sa.key -pubout -out /etc/kubernetes/pki/sa.pub

kubeadm init \
  --service-account-signing-key-file /etc/kubernetes/pki/sa.key \
  --service-account-issuer "https://${PUBLIC_IP}"
```

### Self-registration flow

The instance calls `POST /clusters` on mgmt-service using SigV4 signed by the instance profile. The instance profile role has `execute-api:Invoke` scoped to the registration endpoint only. No shared secret or bootstrap token is needed.

In practice, use the `eks-dx` CLI (already handles SigV4) from the instance:

```bash
eks-dx register-cluster ${TENANT_ID} \
  --issuer "https://${PUBLIC_IP}" \
  --jwks-file /tmp/jwks.json
```

### Instance progress writes directly to DynamoDB

The EC2 instance writes progress directly to the `eks-dx-tenants` DynamoDB table via its instance profile role (`dynamodb:UpdateItem` scoped to the tenant's own item). This is simpler and more reliable than a callback to the provisioning Lambda — no HTTP call, no retry logic, no additional Lambda invocation.

### Async API (mandatory — API Gateway 29s timeout)

```
POST /tenants          → 202 { tenantId }
GET  /tenants/{id}     → { state, phase, progress, publicIp, sshKeySecretArn, updatedAt, error }
DELETE /tenants/{id}   → deprovision
GET  /tenants/{id}/stream  → SSE (Lambda Function URL only — not API Gateway)
```

### IAM role per tenant

A new IAM role is created per tenant with least-privilege inline policy:

```json
[
  { "Effect": "Allow",
    "Action": "secretsmanager:GetSecretValue",
    "Resource": "arn:aws:secretsmanager:*:*:secret:eks-dx/tenant/{tenantId}/*" },
  { "Effect": "Allow",
    "Action": "execute-api:Invoke",
    "Resource": "arn:aws:execute-api:{region}:{accountId}:{apiId}/*/POST/clusters" },
  { "Effect": "Allow",
    "Action": "dynamodb:UpdateItem",
    "Resource": "arn:aws:dynamodb:{region}:{accountId}:table/eks-dx-tenants",
    "Condition": { "ForAllValues:StringEquals": { "dynamodb:LeadingKeys": ["{tenantId}"] } } }
]
```

The role is deleted on tenant deprovisioning.

## Secrets Layout

| Secret name | Content | Lifecycle |
|---|---|---|
| `eks-dx/tenant/{id}/signing-key` | RSA-2048 private key PEM (SA signing) | Persist for cluster lifetime |
| `eks-dx/tenant/{id}/ssh-key` | EC2 key pair private key PEM | Persist for cluster lifetime |

## Deprovisioning

```
DELETE /tenants/{id}
  1. ec2:TerminateInstances
  2. DELETE /clusters/{tenantId}  (mgmt-service — removes DynamoDB entry)
  3. secretsmanager:DeleteSecret eks-dx/tenant/{id}/signing-key
  4. secretsmanager:DeleteSecret eks-dx/tenant/{id}/ssh-key
  5. ec2:DeleteKeyPair eks-dx-tenant-{id}
  6. iam:DeleteRolePolicy + iam:DeleteRole eks-dx-tenant-{id}-instance-role
  7. DynamoDB.delete({ tenantId })
```
