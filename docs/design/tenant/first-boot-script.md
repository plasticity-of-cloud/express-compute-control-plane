# EKS-D-Xpress First-Boot Script Design

Reference for implementing the first-boot script bundled in the EKS-D-Xpress AMI
(`ecp-eks-dx-infra`). This script runs once on first boot, driven by `cloud-init`
or a `systemd` one-shot unit.

## Progress reporting

The instance writes progress directly to the `eks-dx-tenants` DynamoDB table.
The SSE Lambda (`eks-dx-tenant-service`) polls this table every 5 seconds and
streams the current state to the CLI/UI. No callback to Lambda — one `UpdateItem`
call per step.

### State machine

| `state` | `progress` | Description |
|---|---|---|
| `provisioning` | 0 | Set by provisioner Lambda before EC2 launch |
| `booting` | 10 | First line of first-boot script |
| `pulling-key` | 20 | Fetching SA signing key from Secrets Manager (full variant only) |
| `kubeadm-init` | 40 | `kubeadm init` running |
| `kubeadm-done` | 70 | `kubeadm init` completed |
| `registering` | 85 | Calling `eks-dx register-cluster` |
| `ready` | 100 | Cluster registered, in-cluster components installed |
| `failed` | — | Error stored in `error` field |

### DynamoDB update helper

```bash
update_progress() {
  local state=$1 phase=$2 progress=$3
  aws dynamodb update-item \
    --table-name "${EKS_DX_TENANTS_TABLE:-eks-dx-tenants}" \
    --key "{\"tenantId\":{\"S\":\"${TENANT_ID}\"}}" \
    --update-expression "SET #s = :s, phase = :p, progress = :n, updatedAt = :t" \
    --expression-attribute-names '{"#s":"state"}' \
    --expression-attribute-values "{
      \":s\":{\"S\":\"${state}\"},
      \":p\":{\"S\":\"${phase}\"},
      \":n\":{\"N\":\"${progress}\"},
      \":t\":{\"S\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}
    }" \
    --region "${AWS_REGION:-us-east-1}"
}

fail() {
  local msg=$1
  aws dynamodb update-item \
    --table-name "${EKS_DX_TENANTS_TABLE:-eks-dx-tenants}" \
    --key "{\"tenantId\":{\"S\":\"${TENANT_ID}\"}}" \
    --update-expression "SET #s = :s, #e = :e, updatedAt = :t" \
    --expression-attribute-names '{"#s":"state","#e":"error"}' \
    --expression-attribute-values "{
      \":s\":{\"S\":\"failed\"},
      \":e\":{\"S\":\"${msg}\"},
      \":t\":{\"S\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}
    }" \
    --region "${AWS_REGION:-us-east-1}"
  exit 1
}
```

## Required environment / instance metadata

The script reads these at startup:

```bash
# From EC2 instance metadata
PUBLIC_IP=$(curl -sf http://169.254.169.254/latest/meta-data/public-ipv4)
INSTANCE_ID=$(curl -sf http://169.254.169.254/latest/meta-data/instance-id)

# From EC2 instance tags (set by provisioner Lambda)
TENANT_ID=$(aws ec2 describe-tags \
  --filters "Name=resource-id,Values=${INSTANCE_ID}" \
            "Name=key,Values=eks-dx-tenant" \
  --query 'Tags[0].Value' --output text \
  --region "${AWS_REGION:-us-east-1}")
```

The instance profile role has `ec2:DescribeTags` implicitly via the metadata service.
Alternatively, pass `TENANT_ID` via EC2 user data (set by provisioner Lambda at launch time).

## Full variant script (pre-provisioned SA signing key)

```bash
#!/bin/bash
set -euo pipefail
trap 'fail "Unexpected error at line ${LINENO}: ${BASH_COMMAND}"' ERR

# --- Bootstrap ---
PUBLIC_IP=$(curl -sf http://169.254.169.254/latest/meta-data/public-ipv4)
INSTANCE_ID=$(curl -sf http://169.254.169.254/latest/meta-data/instance-id)
# TENANT_ID injected via user data or tag lookup (see above)

update_progress "booting" "Instance first boot" 10

# --- Fetch SA signing key ---
update_progress "pulling-key" "Fetching SA signing key from Secrets Manager" 20

mkdir -p /etc/kubernetes/pki
aws secretsmanager get-secret-value \
  --secret-id "eks-dx/t/${TENANT_ID}/signing-key" \
  --query SecretString --output text \
  --region "${AWS_REGION}" > /etc/kubernetes/pki/sa.key
chmod 600 /etc/kubernetes/pki/sa.key

openssl rsa -in /etc/kubernetes/pki/sa.key \
  -pubout -out /etc/kubernetes/pki/sa.pub 2>/dev/null

# --- kubeadm init ---
update_progress "kubeadm-init" "kubeadm init running" 40

kubeadm init \
  --service-account-signing-key-file /etc/kubernetes/pki/sa.key \
  --service-account-issuer "https://${PUBLIC_IP}" \
  --pod-network-cidr 10.244.0.0/16 \
  >> /var/log/eks-dx-init.log 2>&1

update_progress "kubeadm-done" "kubeadm init completed" 70

# Set up kubeconfig for ec2-user
mkdir -p /home/ec2-user/.kube
cp /etc/kubernetes/admin.conf /home/ec2-user/.kube/config
chown ec2-user:ec2-user /home/ec2-user/.kube/config

# --- Register cluster ---
update_progress "registering" "Registering cluster with eks-dx" 85

# Derive JWKS from sa.pub
kubectl get --raw /openid/v1/jwks \
  --kubeconfig /etc/kubernetes/admin.conf > /tmp/jwks.json

eks-dx register-cluster "${CLUSTER_NAME}" \
  --issuer "https://${PUBLIC_IP}" \
  --jwks-file /tmp/jwks.json

# --- Install in-cluster components ---
# Images are pre-baked in the AMI; Helm charts are bundled at /opt/eks-dx/charts/
helm install eks-dx-auth-proxy /opt/eks-dx/charts/eks-dx-auth-proxy \
  --namespace kube-system \
  --set app.envs.EKS_DX_ENDPOINT="${EKS_DX_ENDPOINT}" \
  --set app.envs.EKS_CLUSTER_NAME="${CLUSTER_NAME}" \
  --kubeconfig /etc/kubernetes/admin.conf

helm install eks-dx-pod-identity-webhook /opt/eks-dx/charts/eks-dx-pod-identity-webhook \
  --namespace kube-system \
  --set app.envs.EKS_DX_ENDPOINT="${EKS_DX_ENDPOINT}" \
  --set app.envs.EKS_CLUSTER_NAME="${CLUSTER_NAME}" \
  --kubeconfig /etc/kubernetes/admin.conf

# --- Done ---
# Write publicIp to DynamoDB so CLI/UI can display it
aws dynamodb update-item \
  --table-name "${EKS_DX_TENANTS_TABLE:-eks-dx-tenants}" \
  --key "{\"tenantId\":{\"S\":\"${TENANT_ID}\"}}" \
  --update-expression "SET #s = :s, phase = :p, progress = :n, publicIp = :ip, updatedAt = :t" \
  --expression-attribute-names '{"#s":"state"}' \
  --expression-attribute-values "{
    \":s\":{\"S\":\"ready\"},
    \":p\":{\"S\":\"Cluster registered\"},
    \":n\":{\"N\":\"100\"},
    \":ip\":{\"S\":\"${PUBLIC_IP}\"},
    \":t\":{\"S\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}
  }" \
  --region "${AWS_REGION}"
```

## CE variant (kubeadm-generated signing key)

Same script but skip the `pulling-key` step — remove the Secrets Manager fetch and
pass no `--service-account-signing-key-file` to `kubeadm init`. kubeadm generates
`sa.key` and `sa.pub` automatically.

```bash
update_progress "booting" "Instance first boot" 10
# (no pulling-key step)
update_progress "kubeadm-init" "kubeadm init running" 40

kubeadm init \
  --service-account-issuer "https://${PUBLIC_IP}" \
  --pod-network-cidr 10.244.0.0/16 \
  >> /var/log/eks-dx-init.log 2>&1

# sa.key and sa.pub are now at /etc/kubernetes/pki/sa.{key,pub}
# rest of script is identical
```

## Environment variables required at boot

Set these via EC2 user data (injected by provisioner Lambda at `RunInstances` time):

```bash
export TENANT_ID=acme-staging
export AWS_REGION=us-east-1
export EKS_DX_ENDPOINT=https://abc123.execute-api.us-east-1.amazonaws.com/prod
export EKS_DX_TENANTS_TABLE=eks-dx-tenants   # optional, defaults to eks-dx-tenants
```

## IAM permissions used by the script

All granted by the per-tenant instance profile role created by the provisioner Lambda:

| Action | Resource | Used for |
|---|---|---|
| `secretsmanager:GetSecretValue` | `eks-dx/t/{tenantId}/*` | SA signing key fetch |
| `execute-api:Invoke` | `POST /clusters/{tenantId}` | Cluster self-registration |
| `dynamodb:UpdateItem` | `eks-dx-tenants` (own item only) | Progress reporting |

## Bundling in the AMI

The AMI build (`ecp-eks-dx-infra`) should:

1. Install: `kubeadm`, `kubelet`, `kubectl`, `eks-dx` CLI, `helm`, `aws-cli v2`
2. Pre-pull container images: `eks-dx-auth-proxy`, `eks-dx-pod-identity-webhook`
3. Bundle Helm charts at `/opt/eks-dx/charts/`
4. Install the first-boot script at `/opt/eks-dx/first-boot.sh` (chmod +x)
5. Install a `systemd` one-shot unit that runs the script on first boot:

```ini
# /etc/systemd/system/eks-dx-first-boot.service
[Unit]
Description=EKS-DX first-boot cluster initialization
After=network-online.target cloud-init.service
Wants=network-online.target
ConditionPathExists=!/var/lib/eks-dx-first-boot.done

[Service]
Type=oneshot
User=root
ExecStart=/opt/eks-dx/first-boot.sh
ExecStartPost=/usr/bin/touch /var/lib/eks-dx-first-boot.done
RemainAfterExit=yes
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

The `ConditionPathExists=!...done` sentinel ensures the script runs exactly once
even if the instance is rebooted before completion.
