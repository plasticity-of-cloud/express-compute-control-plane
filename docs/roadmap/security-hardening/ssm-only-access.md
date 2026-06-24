# Security Hardening — SSM-Only Access (Zero Inbound Ports)

## Status: Planned

## Current State

Tenant instances expose inbound ports via per-tenant security groups:
- **Port 22** (SSH) — restricted to caller's IP CIDR (`sshCidr`)
- **Port 6443** (kube-apiserver) — accessible from VPC CIDR

Access artifacts returned on provisioning:
- `publicIp` (or Elastic IP)
- `sshPrivateKey` (from Secrets Manager, returned in SSE stream when state=ready)
- No kubeconfig generated yet (planned in KUBE_API_PROXY_ARCHITECTURE)

**Problems:**
- Port 22 open to a CIDR — IP can change, CIDR can be too broad
- Public IP exposure increases attack surface
- SSH key management burden on users
- No audit trail for SSH sessions
- kube-apiserver reachable from entire VPC CIDR

## Target State

**Zero inbound security group rules.** All access via SSM Session Manager:

```
Developer workstation
    │
    ├─ ssh (via SSM): aws ssm start-session --target i-xxx --document-name AWS-StartSSHSession
    │
    └─ kubectl (via SSM port forward): aws ssm start-session --target i-xxx \
           --document-name AWS-StartPortForwardingSession \
           --parameters portNumber=6443,localPortNumber=6443
```

On `create tenant` and `resume tenant`, the CLI outputs:
1. **Kubeconfig** — pointing at `https://localhost:6443` with an SSM helper script
2. **SSH command** — `aws ssm start-session` (no key needed)

## Design

### Security Group Changes

Remove all inbound rules. Keep only:
- Egress: all (unchanged)
- Inbound from VPC CIDR on port 6443: **removed** (SSM tunnels from localhost)
- Inbound on port 22: **removed**

```java
// TenantNetworkService — no more SSH or kube-apiserver ingress
// Only internal node-to-node if multi-node (future), otherwise zero inbound
```

### Instance Requirements

SSM Agent must be running. Already satisfied:
- Amazon Linux 2023 / EKS-D AMIs ship with SSM Agent pre-installed
- Instance IAM role already has `ssm:*` in the tenant role policy

Verify the tenant IAM role includes:
```json
{
  "Effect": "Allow",
  "Action": [
    "ssm:UpdateInstanceInformation",
    "ssmmessages:CreateControlChannel",
    "ssmmessages:CreateDataChannel",
    "ssmmessages:OpenControlChannel",
    "ssmmessages:OpenDataChannel"
  ],
  "Resource": "*"
}
```

### CLI Output on `create tenant` / `resume tenant`

#### Kubeconfig (written to `~/.eks-dx/tenants/{id}/kubeconfig`)

```yaml
apiVersion: v1
kind: Config
clusters:
- cluster:
    server: https://127.0.0.1:6443
    certificate-authority-data: <tenant CA, base64>
  name: eks-dx-{tenant-id}
contexts:
- context:
    cluster: eks-dx-{tenant-id}
    user: eks-dx-{tenant-id}-admin
  name: eks-dx-{tenant-id}
current-context: eks-dx-{tenant-id}
users:
- user:
    client-certificate-data: <admin cert, base64>
    client-key-data: <admin key, base64>
```

#### Connection helper (written to `~/.eks-dx/tenants/{id}/connect.sh`)

```bash
#!/usr/bin/env bash
# SSM port-forward to kube-apiserver
INSTANCE_ID="i-0abc123..."
aws ssm start-session \
  --target "$INSTANCE_ID" \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["6443"],"localPortNumber":["6443"]}' \
  --region us-east-1
```

#### CLI UX

```
$ eks-dx tenant create --arch arm64 --k8s-version 1.31
✓ Provisioning complete (62s)

  Instance:  i-0abc123def456
  Region:    us-east-1

  kubectl access (SSM port-forward):
    Terminal 1:  eks-dx connect my-tenant
    Terminal 2:  export KUBECONFIG=~/.eks-dx/tenants/my-tenant/kubeconfig
                 kubectl get nodes

  SSH access:
    eks-dx ssh my-tenant
    # or directly:
    aws ssm start-session --target i-0abc123def456
```

The `eks-dx connect` command wraps `aws ssm start-session` with the correct parameters.
The `eks-dx ssh` command wraps `aws ssm start-session --document-name AWS-StartSSHSession`.

### What Gets Removed

| Current | After |
|---------|-------|
| `sshCidr` parameter on create | Removed (no inbound rules needed) |
| `assignElasticIp` for SSH access | Optional — only for OIDC issuer endpoint |
| Port 22 SG ingress rule | Removed |
| Port 6443 SG ingress from VPC CIDR | Removed |
| `sshPrivateKey` in SSE response | Removed (SSM doesn't need keys) |
| SSH key stored in Secrets Manager | Removed (or kept only for node-internal use) |

### What Gets Added

| Component | Purpose |
|-----------|---------|
| `eks-dx connect <tenant>` CLI command | Wraps SSM port-forward to 6443 |
| `eks-dx ssh <tenant>` CLI command | Wraps SSM session |
| Kubeconfig generation in tenant-service | Returns kubeconfig pointing at localhost:6443 |
| Admin client cert in Secrets Manager | For kubeconfig (replaces SSH key as the "access secret") |
| SSM managed policy on tenant instance role | Ensure SSM connectivity |

### Caller IAM Requirements

Developers/CI need permission to start SSM sessions to tenant instances:

```json
{
  "Effect": "Allow",
  "Action": "ssm:StartSession",
  "Resource": [
    "arn:aws:ec2:*:*:instance/*",
    "arn:aws:ssm:*:*:document/AWS-StartSSHSession",
    "arn:aws:ssm:*:*:document/AWS-StartPortForwardingSession"
  ],
  "Condition": {
    "StringLike": {
      "ssm:resourceTag/eks-dx-tenant": "*"
    }
  }
}
```

This scopes SSM access to only eks-dx tenant instances via resource tags.

## Benefits

| Benefit | Detail |
|---------|--------|
| Zero inbound ports | No SSH, no kube-apiserver exposed to network |
| No key management | SSM uses IAM — no SSH keys to rotate/leak |
| Full audit trail | CloudTrail logs every SSM session (who, when, duration) |
| No public IP required | SSM works via outbound HTTPS (443) to SSM endpoints |
| Works with private subnets | No NAT needed for SSM (VPC endpoints available) |
| IP-agnostic | No `sshCidr` — works from any network |

## Migration Path

### Phase 1 — Add SSM commands to CLI (non-breaking)

- Add `eks-dx connect` and `eks-dx ssh` commands
- Generate kubeconfig on provisioning (store in Secrets Manager)
- Keep existing SG rules and SSH key flow
- Document SSM as the recommended access method

### Phase 2 — Default to SSM-only (breaking for SSH key users)

- Remove `sshCidr` parameter (or ignore it)
- Remove port 22 and 6443 inbound SG rules
- Stop generating/returning SSH private keys
- `assignElasticIp` only for OIDC issuer (not SSH)

### Phase 3 — VPC Endpoints for SSM (fully private)

- Add SSM VPC endpoints (`ssm`, `ssmmessages`, `ec2messages`)
- Tenant instances no longer need any internet access for management
- Combine with the Lambda kube-proxy for CI/CD (non-interactive kubectl)

## Interaction with Kube-API Proxy (KUBE_API_PROXY_ARCHITECTURE)

Both approaches serve different use cases:

| Access Pattern | Mechanism | Use Case |
|----------------|-----------|----------|
| Interactive `kubectl` (developer) | SSM port-forward → localhost:6443 | Day-to-day development |
| CI/CD `kubectl` (non-interactive) | Lambda proxy → private IP:6443 | GitHub Actions, pipelines |
| Wake-on-request | Lambda proxy (detects stopped, resumes) | Cost optimization |

SSM port-forward requires the instance to be running. The Lambda proxy handles wake-on-request for CI/CD. They complement each other.

## Risk Assessment

| Risk | Mitigation |
|------|-----------|
| SSM Agent not running | Health check during provisioning; user-data ensures agent starts |
| Developer lacks SSM IAM permissions | CLI validates permissions upfront, provides clear error |
| Latency of SSM tunnel | ~10-20ms overhead — imperceptible for kubectl |
| SSM service outage | Rare; fallback: temporarily add SG rule via `eks-dx emergency-access` |
