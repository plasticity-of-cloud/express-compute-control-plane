#!/bin/bash
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Defaults
TARGET="all"       # all | proxy | cli | webhook
BUILD_TYPE="jvm"   # jvm | native
PUSH=false
TAG="latest"
REGISTRY=""
AWS_REGION="${AWS_REGION:-us-east-1}"
ECR=false          # auto-set when registry looks like ECR

REPOS=(
    "aws-eks-auth-service-proxy"
    "eks-d-auth-cli"
    "eks-pod-identity-webhook"
)

usage() {
    cat <<EOF
Usage: $0 [OPTIONS]

Options:
  --target TARGET    What to build: all (default), proxy, cli, webhook
  --native           Build native executable (uses GraalVM container build)
  --push             Push container image after build
  --tag TAG          Image tag (default: latest)
  --registry REG     Container registry prefix
                     For ECR: <account>.dkr.ecr.<region>.amazonaws.com
  --region REGION    AWS region for ECR (default: \$AWS_REGION or us-east-1)
  --ecr              Create ECR repos and authenticate before building
  --help             Show this help
EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --target)   TARGET="$2";      shift 2 ;;
        --native)   BUILD_TYPE="native"; shift ;;
        --push)     PUSH=true;         shift ;;
        --tag)      TAG="$2";          shift 2 ;;
        --registry) REGISTRY="$2";     shift 2 ;;
        --region)   AWS_REGION="$2";   shift 2 ;;
        --ecr)      ECR=true;          shift ;;
        --help)     usage ;;
        *) echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
    esac
done

# Auto-detect ECR registry pattern
if [[ "$REGISTRY" =~ \.dkr\.ecr\. ]]; then
    ECR=true
fi

image_name() {
    local name="$1"
    if [[ -n "$REGISTRY" ]]; then echo "$REGISTRY/$name:$TAG"
    else echo "$name:$TAG"
    fi
}

# ── ECR helpers ────────────────────────────────────────────────────────────────

ecr_ensure_repo() {
    local repo="$1"
    if ! aws ecr describe-repositories --repository-names "$repo" --region "$AWS_REGION" &>/dev/null; then
        echo -e "${YELLOW}  Creating ECR repo: $repo${NC}"
        aws ecr create-repository \
            --repository-name "$repo" \
            --region "$AWS_REGION" \
            --image-scanning-configuration scanOnPush=true \
            --encryption-configuration encryptionType=AES256 \
            --output text --query 'repository.repositoryUri'
    else
        echo "  ECR repo exists: $repo"
    fi
}

ecr_login() {
    echo -e "${YELLOW}Authenticating with ECR ($AWS_REGION)...${NC}"
    local account_id
    account_id=$(aws sts get-caller-identity --query Account --output text)
    local ecr_host="${account_id}.dkr.ecr.${AWS_REGION}.amazonaws.com"

    aws ecr get-login-password --region "$AWS_REGION" \
        | docker login --username AWS --password-stdin "$ecr_host"

    # Set registry if not already set
    if [[ -z "$REGISTRY" ]]; then
        REGISTRY="$ecr_host"
        echo -e "${GREEN}ECR registry: $REGISTRY${NC}"
    fi
}

ecr_setup() {
    ecr_login
    echo -e "${YELLOW}Ensuring ECR repositories exist...${NC}"
    for repo in "${REPOS[@]}"; do
        ecr_ensure_repo "$repo"
    done
}

# ── Build functions ────────────────────────────────────────────────────────────

MVN="mvn"

_build() {
    local modules="$1"
    local img="$2"
    local native_flag=""
    [[ "$BUILD_TYPE" == "native" ]] && native_flag="-Pnative"

    # Set resource limits for build process
    export MAVEN_OPTS="-Xmx10g"
    
    $MVN -pl "$modules" package $native_flag -DskipTests \
        -Dquarkus.container-image.build=true \
        -Dquarkus.container-image.image="$img" \
        -Dquarkus.jib.jvm-arguments=-Xmx8g \
        ${PUSH:+-Dquarkus.container-image.push=true}
}

build_proxy() {
    local img; img=$(image_name "aws-eks-auth-service-proxy")
    echo -e "${YELLOW}Building eks-auth-proxy ($BUILD_TYPE)...${NC}"
    _build "eks-pod-identity-crd,eks-auth-proxy" "$img"
    echo -e "${GREEN}proxy image: $img${NC}"
}

build_cli() {
    local img; img=$(image_name "eks-d-auth-cli")
    echo -e "${YELLOW}Building eks-d-auth-cli (native for CLI tool)...${NC}"
    
    # Force native build for CLI tool regardless of BUILD_TYPE
    export MAVEN_OPTS="-Xmx10g"
    
    $MVN -pl "eks-pod-identity-crd,eks-d-auth-cli" package -Pnative -DskipTests \
        -Dquarkus.container-image.build=true \
        -Dquarkus.container-image.image="$img"
    
    echo -e "${GREEN}cli image (native): $img${NC}"
}

build_webhook() {
    local img; img=$(image_name "eks-pod-identity-webhook")
    echo -e "${YELLOW}Building eks-pod-identity-webhook ($BUILD_TYPE)...${NC}"
    _build "eks-pod-identity-webhook" "$img"
    echo -e "${GREEN}webhook image: $img${NC}"
}

# ── Main ───────────────────────────────────────────────────────────────────────

echo -e "${GREEN}=== EKS Auth Build ===${NC}"
echo "target=$TARGET  type=$BUILD_TYPE  push=$PUSH  tag=$TAG  ecr=$ECR"
[[ -n "$REGISTRY" ]] && echo "registry=$REGISTRY"
echo

[[ "$ECR" == true ]] && ecr_setup

case "$TARGET" in
    proxy)   build_proxy ;;
    cli)     build_cli ;;
    webhook) build_webhook ;;
    all)     build_proxy; build_cli; build_webhook ;;
    *) echo -e "${RED}Unknown target: $TARGET${NC}"; exit 1 ;;
esac

echo -e "${GREEN}Done.${NC}"
