#!/bin/bash
#
# Automated setup: EC2 + k3s for EKS-DX Pod Identity
#
# Prerequisites:
#   - Lambda backend deployed (sam deploy)
#   - AWS CLI v2 configured
#
# Usage:
#   ./setup.sh --key-pair my-key --region us-east-1 \
#     --eks-dx-endpoint https://xxx.execute-api.us-east-1.amazonaws.com/prod
#
# The key pair will be created automatically if it does not exist.
# The private key is saved to ./<key-pair-name>.pem
#
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}[+]${NC} $*"; }
warn() { echo -e "${YELLOW}[!]${NC} $*"; }
err()  { echo -e "${RED}[✗]${NC} $*" >&2; exit 1; }

REGION="${AWS_REGION:-us-east-1}"
CLUSTER_NAME="eks-dx-control-plane-k3s"
INSTANCE_TYPE="t4g.medium"
KEY_PAIR="k3s-pod-id-key"
EKS_DX_ENDPOINT=""
SG_NAME=""  # derived from cluster name after arg parsing

usage() {
  cat <<EOF
Usage: $0 --key-pair NAME --eks-dx-endpoint URL [OPTIONS]

Required:
  --key-pair NAME           EC2 key pair name (created if absent, default: k3s-pod-id-key)
  --eks-dx-endpoint URL     EKS-DX Lambda API endpoint

Options:
  --region REGION           AWS region            (default: $REGION)
  --cluster-name NAME       Cluster name          (default: $CLUSTER_NAME)
  --instance-type TYPE      EC2 instance type     (default: $INSTANCE_TYPE)
  --help                    Show this help
EOF
  exit 0
}

while [[ $# -gt 0 ]]; do
  case $1 in
    --key-pair)         KEY_PAIR="$2";         shift 2 ;;
    --eks-dx-endpoint)  EKS_DX_ENDPOINT="$2";  shift 2 ;;
    --region)           REGION="$2";           shift 2 ;;
    --cluster-name)     CLUSTER_NAME="$2";     shift 2 ;;
    --instance-type)    INSTANCE_TYPE="$2";    shift 2 ;;
    --help)             usage ;;
    *) err "Unknown option: $1" ;;
  esac
done

[[ -z "$EKS_DX_ENDPOINT" ]] && err "--eks-dx-endpoint is required"

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
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
log "SSH access restricted to $MY_IP"

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
       eks-dx create cluster --name ${CLUSTER_NAME} --region ${REGION}

  2. SSH in if needed:
       ssh -i ${KEY_PAIR}.pem ubuntu@${PUBLIC_IP}

  3. Create associations:
       eks-dx create pod-identity-association \\
         --cluster-name ${CLUSTER_NAME} \\
         --namespace default --service-account my-app \\
         --role-arn arn:aws:iam::${ACCOUNT_ID}:role/eks-dx-pod-my-app

  4. Deploy in-cluster components (proxy, agent, webhook)
     See: docs/user_guides/ec2-k3s-pod-identity/README.md

SUMMARY
