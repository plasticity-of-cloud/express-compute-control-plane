#!/bin/bash
#
# Automated setup: EC2 + k3s for EKS-DX Pod Identity
#
# Prerequisites:
#   - Lambda backend deployed (./deploy-local.sh — see README.md)
#   - AWS CLI v2 configured
#   - helm 3 installed locally
#
# Usage:
#   ./setup.sh --eks-dx-endpoint https://xxx.execute-api.us-east-1.amazonaws.com/prod \
#     --version 1.0.0
#
# All AWS resources (key pair, security group, EC2 instance) are named after --cluster-name.
# The private key is saved to ./<cluster-name>.pem
#
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}[+]${NC} $*"; }
warn() { echo -e "${YELLOW}[!]${NC} $*"; }
err()  { echo -e "${RED}[✗]${NC} $*" >&2; exit 1; }

REGION="${AWS_REGION:-us-east-1}"
CLUSTER_NAME="eks-dx-k3s"
INSTANCE_TYPE="t4g.medium"
EKS_DX_ENDPOINT=""
EKS_DX_VERSION=""          # e.g. 1.0.0 — pulls from GHCR when set
GITHUB_ORG="codriverlabs"
SG_NAME=""  # derived from cluster name after arg parsing
KEY_PAIR=""  # derived from cluster name after arg parsing

usage() {
  cat <<EOF
Usage: $0 --eks-dx-endpoint URL [OPTIONS]

Required:
  --eks-dx-endpoint URL     EKS-DX Lambda API endpoint

Options:
  --cluster-name NAME       Cluster name          (default: $CLUSTER_NAME)
                            Also used as prefix for key-pair, security group, and EC2 hostname.
  --version VERSION         EKS-DX release version to deploy from GHCR (e.g. 1.0.0)
                            If omitted, in-cluster components are NOT installed automatically.
  --region REGION           AWS region            (default: $REGION)
  --instance-type TYPE      EC2 instance type     (default: $INSTANCE_TYPE)
  --help                    Show this help
EOF
  exit 0
}

while [[ $# -gt 0 ]]; do
  case $1 in
    --eks-dx-endpoint)  EKS_DX_ENDPOINT="$2";  shift 2 ;;
    --version)          EKS_DX_VERSION="$2";   shift 2 ;;
    --region)           REGION="$2";           shift 2 ;;
    --cluster-name)     CLUSTER_NAME="$2";     shift 2 ;;
    --instance-type)    INSTANCE_TYPE="$2";    shift 2 ;;
    --help)             usage ;;
    *) err "Unknown option: $1" ;;
  esac
done

[[ -z "$EKS_DX_ENDPOINT" ]] && err "--eks-dx-endpoint is required"

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
KEY_PAIR="${CLUSTER_NAME}"
SG_NAME="${CLUSTER_NAME}-sg"
log "Account: $ACCOUNT_ID  Region: $REGION  Cluster: $CLUSTER_NAME"

# ── 0. Key pair ──────────────────────────────────────────────────────
PEM_FILE="${KEY_PAIR}.pem"
if aws ec2 describe-key-pairs --key-names "$KEY_PAIR" --region "$REGION" &>/dev/null; then
  warn "Key pair '$KEY_PAIR' already exists in AWS — skipping creation"
  [[ ! -f "$PEM_FILE" ]] && warn "Private key $PEM_FILE not found locally — make sure you have it"
else
  log "Creating key pair '$KEY_PAIR' ..."
  aws ec2 create-key-pair \
    --key-name "$KEY_PAIR" \
    --query 'KeyMaterial' --output text \
    --region "$REGION" > "$PEM_FILE"
  chmod 600 "$PEM_FILE"
  log "Private key saved to $PEM_FILE"
fi

# ── 1. Security group ────────────────────────────────────────────────
log "Creating security group ($SG_NAME) ..."

VPC_ID=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true \
  --query 'Vpcs[0].VpcId' --output text --region "$REGION")

SG_ID=$(aws ec2 create-security-group \
  --group-name "$SG_NAME" \
  --description "k3s pod identity cluster" \
  --vpc-id "$VPC_ID" \
  --query 'GroupId' --output text --region "$REGION" 2>/dev/null || \
  aws ec2 describe-security-groups --group-names "$SG_NAME" \
  --query 'SecurityGroups[0].GroupId' --output text --region "$REGION")

MY_IP=$(curl -sf https://checkip.amazonaws.com) || err "Could not determine public IP"
aws ec2 authorize-security-group-ingress \
  --group-id "$SG_ID" --protocol tcp --port 22 --cidr "${MY_IP}/32" \
  --region "$REGION" 2>/dev/null || true
aws ec2 authorize-security-group-ingress \
  --group-id "$SG_ID" --protocol tcp --port 6443 --cidr "${MY_IP}/32" \
  --region "$REGION" 2>/dev/null || true
log "SSH and k3s API (6443) access restricted to $MY_IP"

# ── 2. Launch EC2 with k3s ───────────────────────────────────────────
read -r -d '' USERDATA <<'CLOUD_INIT' || true
#!/bin/bash
set -e
apt-get update -qq && apt-get install -y -qq curl jq git > /dev/null
PUBLIC_IP=$(curl -sf http://169.254.169.254/latest/meta-data/public-ipv4)
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--disable traefik --disable servicelb --tls-san ${PUBLIC_IP}" sh -
mkdir -p /home/ubuntu/.kube
cp /etc/rancher/k3s/k3s.yaml /home/ubuntu/.kube/config
chown -R ubuntu:ubuntu /home/ubuntu/.kube
chmod 600 /home/ubuntu/.kube/config
echo "export KUBECONFIG=/home/ubuntu/.kube/config" >> /home/ubuntu/.bashrc
curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
CLOUD_INIT

log "Looking up latest Ubuntu 22.04 ARM64 AMI ..."
AMI_ID=$(aws ec2 describe-images --owners 099720109477 \
  --filters "Name=name,Values=ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-arm64-server-*" \
            "Name=state,Values=available" \
  --query 'Images | sort_by(@, &CreationDate) | [-1].ImageId' \
  --output text --region "$REGION")

log "Launching $INSTANCE_TYPE ($AMI_ID) ..."
INSTANCE_ID=$(aws ec2 run-instances \
  --image-id "$AMI_ID" \
  --instance-type "$INSTANCE_TYPE" \
  --key-name "$KEY_PAIR" \
  --security-group-ids "$SG_ID" \
  --user-data "$USERDATA" \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$CLUSTER_NAME}]" \
  --query 'Instances[0].InstanceId' --output text --region "$REGION")

log "Waiting for instance $INSTANCE_ID ..."
aws ec2 wait instance-running --instance-ids "$INSTANCE_ID" --region "$REGION"

PUBLIC_IP=$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text --region "$REGION")

log "Instance ready: $PUBLIC_IP"

# ── 3. Fetch kubeconfig (wait for k3s to be ready) ───────────────────
KUBECONFIG_PATH="/tmp/${CLUSTER_NAME}-kubeconfig.yaml"
log "Waiting for k3s to start (up to 3 min) ..."
for i in $(seq 1 18); do
  if ssh -i "${KEY_PAIR}.pem" -o StrictHostKeyChecking=no -o ConnectTimeout=5 \
       "ubuntu@${PUBLIC_IP}" "test -f /etc/rancher/k3s/k3s.yaml" 2>/dev/null; then
    break
  fi
  sleep 10
done

log "Fetching kubeconfig → $KUBECONFIG_PATH"
scp -i "${KEY_PAIR}.pem" -o StrictHostKeyChecking=no \
  "ubuntu@${PUBLIC_IP}:/etc/rancher/k3s/k3s.yaml" "$KUBECONFIG_PATH"

# Patch: replace loopback with public IP (cert now has IP SAN, so CA trust works)
sed -i "s|https://127.0.0.1:6443|https://${PUBLIC_IP}:6443|g" "$KUBECONFIG_PATH"
log "Kubeconfig ready: $KUBECONFIG_PATH"

# ── 4. Install in-cluster components (if --version supplied) ──────────
if [[ -n "$EKS_DX_VERSION" ]]; then
  log "Installing EKS-DX Pod Identity components v${EKS_DX_VERSION} ..."
  export KUBECONFIG="$KUBECONFIG_PATH"

  # cert-manager (required for webhook TLS)
  kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml
  kubectl wait --for=condition=Available deployment/cert-manager-webhook \
    -n cert-manager --timeout=120s

  # Run the canonical install script released alongside this version
  curl -sL "https://github.com/plasticity-of-cloud/eks-d-xpress-control-plane/releases/download/v${EKS_DX_VERSION}/install-eks-dx-pod-identity-${EKS_DX_VERSION}.sh" \
    | EKS_DX_ENDPOINT="${EKS_DX_ENDPOINT}" \
      CLUSTER_NAME="${CLUSTER_NAME}" \
      AWS_REGION="${REGION}" \
      EKS_DX_VERSION="${EKS_DX_VERSION}" \
      bash

  log "In-cluster components installed."
fi

cat <<SUMMARY

${GREEN}═══════════════════════════════════════════════════════════════${NC}
  EC2 + k3s — ready for EKS-DX Pod Identity
${GREEN}═══════════════════════════════════════════════════════════════${NC}

  Instance:  $INSTANCE_ID ($PUBLIC_IP)
  Region:    $REGION
  Cluster:   $CLUSTER_NAME
  Endpoint:  $EKS_DX_ENDPOINT
  Kubeconfig: $KUBECONFIG_PATH

  ${YELLOW}Next steps:${NC}

  1. Register the cluster:
       export KUBECONFIG=$KUBECONFIG_PATH
       eks-dx configure --endpoint ${EKS_DX_ENDPOINT} --region ${REGION}
       eks-dx register-cluster --name ${CLUSTER_NAME} --region ${REGION}

  2. Create associations:
       eks-dx create-association \\
         --cluster-name ${CLUSTER_NAME} \\
         --namespace default --service-account my-app \\
         --role-arn arn:aws:iam::${ACCOUNT_ID}:role/eks-dx-pod-my-app

$(if [[ -z "$EKS_DX_VERSION" ]]; then
  echo "  3. Deploy in-cluster components:"
  echo "       See: docs/user-guides/ec2-k3s-pod-identity/README.md"
  echo "       Or re-run with --version <version> to install from GHCR automatically."
else
  echo "  In-cluster components (auth-proxy, webhook) installed from GHCR v${EKS_DX_VERSION}."
  echo "  3. Deploy EKS Pod Identity Agent — see README.md step 5."
fi)

  SSH: ssh -i ${KEY_PAIR}.pem ubuntu@${PUBLIC_IP}

SUMMARY
