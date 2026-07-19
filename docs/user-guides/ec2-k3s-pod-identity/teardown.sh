#!/bin/bash
#
# Teardown: remove all resources created by setup.sh for a k3s EKS-DX cluster
#
# Usage:
#   ./teardown.sh --ecp-endpoint URL [--cluster-name NAME] [--region REGION]
#
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}[+]${NC} $*"; }
warn() { echo -e "${YELLOW}[!]${NC} $*"; }
err()  { echo -e "${RED}[✗]${NC} $*" >&2; exit 1; }

REGION="${AWS_REGION:-us-east-1}"
CLUSTER_NAME="ecp-k3s"
ECP_ENDPOINT=""

usage() {
  cat <<EOF
Usage: $0 --ecp-endpoint URL [OPTIONS]

Required:
  --ecp-endpoint URL     EKS-DX Lambda API endpoint (to deregister cluster)

Options:
  --cluster-name NAME       Cluster name (default: $CLUSTER_NAME)
  --region REGION           AWS region   (default: $REGION)
  --help
EOF
  exit 0
}

while [[ $# -gt 0 ]]; do
  case $1 in
    --ecp-endpoint) ECP_ENDPOINT="$2"; shift 2 ;;
    --cluster-name)    CLUSTER_NAME="$2";    shift 2 ;;
    --region)          REGION="$2";          shift 2 ;;
    --help)            usage ;;
    *) err "Unknown option: $1" ;;
  esac
done

[[ -z "$ECP_ENDPOINT" ]] && err "--ecp-endpoint is required"

KEY_PAIR="${CLUSTER_NAME}"
SG_NAME="${CLUSTER_NAME}-sg"
KUBECONFIG_PATH="/tmp/${CLUSTER_NAME}-kubeconfig.yaml"

log "Cluster: $CLUSTER_NAME  Region: $REGION"

# ── 1. Deregister cluster + associations from EKS-DX ─────────────────
CLI_JAR="$(dirname "$0")/../../../../ecp-cli/target/ecp-cli-*-runner.jar"
CLI_JAR=$(ls $CLI_JAR 2>/dev/null | head -1 || true)
CLI_NATIVE="$(dirname "$0")/../../../../ecp-cli/target/eks-dx"

if command -v ecp &>/dev/null; then
  CLI="ecp"
elif [[ -f "$CLI_NATIVE" ]]; then
  CLI="$CLI_NATIVE"
elif [[ -n "$CLI_JAR" && -f "$CLI_JAR" ]]; then
  CLI="java -jar $CLI_JAR"
else
  CLI=""
fi

if [[ -n "$CLI" ]]; then
  log "Deleting pod identity associations ..."
  $CLI list pod-identity-associations \
    --cluster-name "$CLUSTER_NAME" 2>/dev/null | \
    awk '/assoc-/{print $1}' | while read -r ASSOC_ID; do
      $CLI delete pod-identity-association \
        --cluster-name "$CLUSTER_NAME" --association-id "$ASSOC_ID" 2>/dev/null && \
        log "  Deleted association $ASSOC_ID"
    done

  log "Deregistering cluster $CLUSTER_NAME ..."
  $CLI delete cluster --name "$CLUSTER_NAME" 2>/dev/null && \
    log "  Cluster deregistered" || warn "  Cluster not found or already deleted"
else
  warn "ecp CLI not found — skipping ECP deregistration (do it manually)"
fi

# ── 2. Terminate EC2 instance ─────────────────────────────────────────
INSTANCE_ID=$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:Name,Values=${CLUSTER_NAME}" "Name=instance-state-name,Values=running,stopped" \
  --query 'Reservations[0].Instances[0].InstanceId' --output text 2>/dev/null || true)

if [[ -n "$INSTANCE_ID" && "$INSTANCE_ID" != "None" ]]; then
  log "Terminating instance $INSTANCE_ID ..."
  aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "$REGION" > /dev/null
  aws ec2 wait instance-terminated --instance-ids "$INSTANCE_ID" --region "$REGION"
  log "Instance terminated."
else
  warn "No running instance found with Name=$CLUSTER_NAME"
fi

# ── 3. Delete security group ──────────────────────────────────────────
SG_ID=$(aws ec2 describe-security-groups --group-names "$SG_NAME" \
  --query 'SecurityGroups[0].GroupId' --output text --region "$REGION" 2>/dev/null || true)

if [[ -n "$SG_ID" && "$SG_ID" != "None" ]]; then
  log "Deleting security group $SG_NAME ($SG_ID) ..."
  aws ec2 delete-security-group --group-id "$SG_ID" --region "$REGION"
  log "Security group deleted."
else
  warn "Security group $SG_NAME not found"
fi

# ── 4. Delete key pair ────────────────────────────────────────────────
if aws ec2 describe-key-pairs --key-names "$KEY_PAIR" --region "$REGION" &>/dev/null; then
  log "Deleting key pair $KEY_PAIR ..."
  aws ec2 delete-key-pair --key-name "$KEY_PAIR" --region "$REGION"
  [[ -f "${KEY_PAIR}.pem" ]] && rm -f "${KEY_PAIR}.pem" && log "Removed ${KEY_PAIR}.pem"
else
  warn "Key pair $KEY_PAIR not found"
fi

# ── 5. Remove local kubeconfig ────────────────────────────────────────
[[ -f "$KUBECONFIG_PATH" ]] && rm -f "$KUBECONFIG_PATH" && log "Removed $KUBECONFIG_PATH"

log "Teardown complete."
