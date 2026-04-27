#!/bin/bash
#
# Automated setup: EC2 + k3s for EKS-DX Pod Identity
#
# Prerequisites:
#   - Lambda backend deployed (sam deploy)
#   - AWS CLI v2 configured
#   - An existing EC2 key pair
#
# Usage:
#   ./setup.sh --key-pair my-key --region us-east-1 \
#     --eks-dx-endpoint https://xxx.execute-api.us-east-1.amazonaws.com/prod
#
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}[+]${NC} $*"; }
warn() { echo -e "${YELLOW}[!]${NC} $*"; }
err()  { echo -e "${RED}[✗]${NC} $*" >&2; exit 1; }

REGION="${AWS_REGION:-us-east-1}"
CLUSTER_NAME="k3s-pod-id"
INSTANCE_TYPE="t4g.medium"
KEY_PAIR=""
EKS_DX_ENDPOINT=""
SG_NAME="k3s-pod-id-sg"

usage() {
  cat <<EOF
Usage: $0 --key-pair NAME --eks-dx-endpoint URL [OPTIONS]

Required:
  --key-pair NAME           EC2 key pair name
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

[[ -z "$KEY_PAIR" ]] && err "--key-pair is required"
[[ -z "$EKS_DX_ENDPOINT" ]] && err "--eks-dx-endpoint is required"

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
log "Account: $ACCOUNT_ID  Region: $REGION  Cluster: $CLUSTER_NAME"

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

aws ec2 authorize-security-group-ingress \
  --group-id "$SG_ID" --protocol tcp --port 22 --cidr 0.0.0.0/0 \
  --region "$REGION" 2>/dev/null || true

# ── 2. Launch EC2 with k3s ───────────────────────────────────────────
read -r -d '' USERDATA <<'CLOUD_INIT' || true
#!/bin/bash
set -e
apt-get update -qq && apt-get install -y -qq curl jq git > /dev/null
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--disable traefik --disable servicelb" sh -
mkdir -p /home/ubuntu/.kube
cp /etc/rancher/k3s/k3s.yaml /home/ubuntu/.kube/config
sed -i 's/127.0.0.1/0.0.0.0/' /home/ubuntu/.kube/config
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

cat <<SUMMARY

${GREEN}═══════════════════════════════════════════════════════════════${NC}
  EC2 + k3s — ready for EKS-DX Pod Identity
${GREEN}═══════════════════════════════════════════════════════════════${NC}

  Instance:  $INSTANCE_ID ($PUBLIC_IP)
  Region:    $REGION
  Cluster:   $CLUSTER_NAME
  Endpoint:  $EKS_DX_ENDPOINT

  ${YELLOW}Next steps:${NC}

  1. SSH in (wait ~2 min for cloud-init):
       ssh -i ${KEY_PAIR}.pem ubuntu@${PUBLIC_IP}

  2. Register the cluster:
       eks-dx configure --endpoint ${EKS_DX_ENDPOINT} --region ${REGION}
       eks-dx create cluster --name ${CLUSTER_NAME} --region ${REGION}

  3. Create associations:
       eks-dx create pod-identity-association \\
         --cluster-name ${CLUSTER_NAME} \\
         --namespace default --service-account my-app \\
         --role-arn arn:aws:iam::${ACCOUNT_ID}:role/eks-dx-pod-my-app

  4. Deploy in-cluster components (proxy, agent, webhook)
     See: docs/user_guides/ec2-k3s-pod-identity/README.md

SUMMARY
