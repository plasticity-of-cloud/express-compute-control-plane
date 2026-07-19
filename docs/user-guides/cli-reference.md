# Express Compute CLI Reference

## Installation

Build the native binary (requires GraalVM JDK 25):

```bash
./build-local.sh --only cli --native
# Binary: ecp-cli/target/ecp
```

Or run from the uber-jar (JVM, no native build required):

```bash
./build-local.sh --only cli
java -jar ecp-cli/target/*-runner.jar --help
```

---

## Global options

| Flag | Description |
|------|-------------|
| `--help`, `-h` | Print help |
| `--version`, `-V` | Print version |

AWS credentials are resolved via the standard chain: env vars → `~/.aws/credentials` →
instance profile → IAM Identity Center (SSO). The region defaults to `AWS_REGION` env
var or the value stored in `~/.express-compute/config`.

---

## Configuration

### `ecp configure`

Saves the API endpoint and region to `~/.express-compute/config`. Required once before
using any other command.

```bash
ecp configure --endpoint https://<api-id>.execute-api.us-east-1.amazonaws.com \
                 --region us-east-1
```

If the endpoint is already published to SSM (`/express-compute/control-plane/api/endpoint`),
the CLI resolves it automatically and you can skip `configure`.

---

## Cluster commands

### `ecp create-cluster <name>`

Create a managed cluster (full EKS-D provisioning on EC2) or register a self-managed one
(k3s, microk8s, EKS-D kubeadm).

The server infers the mode from the request: providing `--jwks-uri`/`--jwks-file` and
`--issuer` triggers self-managed registration; omitting them triggers full provisioning.

**Managed mode (default)**

```bash
ecp create-cluster my-cluster
ecp create-cluster my-cluster --arch arm64 --pricing spot --k8s-version 1.35
ecp create-cluster my-cluster --wait          # stream provisioning progress
ecp create-cluster my-cluster --ssh-cidr 203.0.113.10/32
```

| Flag | Default | Description |
|------|---------|-------------|
| `--arch` | `arm64` | CPU architecture: `arm64` or `x86_64` |
| `--pricing` | `spot` | EC2 pricing model: `spot` or `ondemand` |
| `--k8s-version` | `1.35` | Kubernetes version |
| `--disk-size` | `20` | Root disk size in GB |
| `--ssh-cidr` | caller IP/32 | CIDR allowed for SSH access |
| `--wait` | off | Stream provisioning progress and save SSH key on completion |
| `--output` | `text` | `text` or `json` |

**Self-managed mode**

```bash
# From a reachable cluster via kubeconfig (auto-discovery):
ecp create-cluster my-k3s --kubeconfig ~/.kube/config

# Explicit JWKS + issuer:
ecp create-cluster my-k3s \
  --jwks-uri https://my-cluster/.well-known/openid/v1/jwks \
  --issuer https://my-cluster

# From a local JWKS file:
ecp create-cluster my-k3s \
  --jwks-file ./jwks.json \
  --issuer https://my-cluster
```

**Error: duplicate name**

If the cluster name already exists the server returns 409 and the CLI prints:

```
Error: Cluster 'my-cluster' already exists. To replace it, run: ecp delete-cluster my-cluster
```

---

### `ecp delete-cluster <name>`

Delete a cluster. For managed clusters performs full teardown (EC2, IAM, network,
secrets). For self-managed clusters removes the DynamoDB registration only.

```bash
ecp delete-cluster my-cluster
```

---

### `ecp describe-cluster <name>`

Show registration details (issuer, JWKS metadata, creation timestamp).

```bash
ecp describe-cluster my-cluster
```

---

### `ecp list-clusters`

List all clusters registered by the current caller.

```bash
ecp list-clusters
ecp list-clusters --output json
```

---

### `ecp update-cluster <name>`

Update the JWKS or issuer for a self-managed cluster.

```bash
ecp update-cluster my-k3s --jwks-file ./new-jwks.json --issuer https://new-issuer
```

---

### `ecp stop-cluster <name>`

Stop (hibernate) a managed cluster's EC2 instance to save cost.

```bash
ecp stop-cluster my-cluster
```

---

### `ecp resume-cluster <name>`

Resume a stopped managed cluster.

```bash
ecp resume-cluster my-cluster
```

---

### `ecp get-cluster-access <name>`

Retrieve the public IP and SSH connection details for a managed cluster on demand.
Useful when the terminal session that ran `create-cluster --wait` is no longer
available, or when connecting from a different machine.

```bash
ecp get-cluster-access my-cluster
```

Example output:

```
  Cluster:    my-cluster
  Public IP:  54.12.34.56
  SSH key:    ~/.express-compute/tenants/us-east-1/a1b2c3d4.pem

  Connect:
  ssh -i ~/.express-compute/tenants/us-east-1/a1b2c3d4.pem ec2-user@54.12.34.56
```

| Flag | Description |
|------|-------------|
| `--save-key` | Re-fetch the SSH private key from Secrets Manager and overwrite the local `.pem` file |
| `--print-key` | Re-fetch the key and print it to stdout (pipe to a file or clipboard) |
| `--output json` | Emit `{ clusterName, tenantId, publicIp, sshKeyPath, sshCommand }` |
| `--region` | Override the AWS region |

**Error cases**

| Condition | Message |
|-----------|---------|
| Cluster not found | `Error: cluster 'X' not found` |
| Self-managed cluster | `Error: cluster 'X' is self-managed and has no SSH access managed by this system` |
| Stopped / hibernating | `Error: cluster 'X' is stopped. Resume it first: ecp resume-cluster X` |
| Still provisioning | `Error: cluster 'X' is not ready yet (state: provisioning, 42%, phase: ...)` |
| No public IP yet | `Error: cluster 'X' has no public IP recorded — it may still be booting` |

**Recovering a lost SSH key**

If you have lost the local `.pem` file, re-fetch it from Secrets Manager:

```bash
ecp get-cluster-access my-cluster --save-key
# Saves to: ~/.express-compute/tenants/<region>/<tenantId>.pem  (chmod 600)
```

---

## Association commands

Pod identity associations map a Kubernetes service account to an IAM role.

### `ecp create-association <cluster> <namespace> <service-account> <role-arn>`

```bash
ecp create-association my-cluster default my-app \
  arn:aws:iam::123456789012:role/my-app-role
```

### `ecp delete-association <cluster> <id>`

```bash
ecp delete-association my-cluster abc12345
```

### `ecp describe-association <cluster> <id>`

```bash
ecp describe-association my-cluster abc12345
```

### `ecp list-associations <cluster>`

```bash
ecp list-associations my-cluster
ecp list-associations my-cluster --output json
```

---

## Typical workflows

### Provision a managed cluster and connect

```bash
# 1. Create and wait — SSH key saved automatically on completion
ecp create-cluster prod --wait

# 2. Later sessions — retrieve connection details on demand
ecp get-cluster-access prod

# 3. If .pem was lost
ecp get-cluster-access prod --save-key
```

### Register a self-managed cluster

```bash
# Auto-discover JWKS + issuer from a reachable cluster
ecp create-cluster my-k3s --kubeconfig ~/.kube/my-k3s.yaml

# Set up workload identity for a workload
ecp create-association my-k3s default my-app \
  arn:aws:iam::123456789012:role/my-app-role
```

### Cost management — stop overnight, resume in the morning

```bash
ecp stop-cluster prod
# ... next day ...
ecp resume-cluster prod
ecp get-cluster-access prod   # confirm IP (may change if not using EIP)
```
