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

usage() {
    cat <<EOF
Usage: $0 [OPTIONS]

Options:
  --target TARGET    What to build: all (default), proxy, cli, webhook
  --native           Build native executable (uses GraalVM container build)
  --push             Push container image after build
  --tag TAG          Image tag (default: latest)
  --registry REG     Container registry prefix (e.g. ghcr.io/plcloud)
  --help             Show this help
EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --target)   TARGET="$2";   shift 2 ;;
        --native)   BUILD_TYPE="native"; shift ;;
        --push)     PUSH=true;     shift ;;
        --tag)      TAG="$2";      shift 2 ;;
        --registry) REGISTRY="$2"; shift 2 ;;
        --help)     usage ;;
        *) echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
    esac
done

image_name() {
    local name="$1"
    if [[ -n "$REGISTRY" ]]; then echo "$REGISTRY/$name:$TAG"
    else echo "$name:$TAG"
    fi
}

MVN="mvn"

build_proxy() {
    local img; img=$(image_name "aws-eks-auth-service-proxy")
    echo -e "${YELLOW}Building eks-auth-proxy ($BUILD_TYPE)...${NC}"

    if [[ "$BUILD_TYPE" == "native" ]]; then
        $MVN -pl eks-pod-identity-crd,eks-auth-proxy package -Pnative \
            -Dquarkus.container-image.build=true \
            -Dquarkus.container-image.image="$img" \
            ${PUSH:+-Dquarkus.container-image.push=true}
    else
        $MVN -pl eks-pod-identity-crd,eks-auth-proxy package \
            -Dquarkus.container-image.build=true \
            -Dquarkus.container-image.image="$img" \
            ${PUSH:+-Dquarkus.container-image.push=true}
    fi

    echo -e "${GREEN}proxy image: $img${NC}"
}

build_cli() {
    local img; img=$(image_name "eks-d-auth-cli")
    echo -e "${YELLOW}Building eks-d-auth-cli ($BUILD_TYPE)...${NC}"

    if [[ "$BUILD_TYPE" == "native" ]]; then
        $MVN -pl eks-pod-identity-crd,eks-d-auth-cli package -Pnative \
            -Dquarkus.container-image.build=true \
            -Dquarkus.container-image.image="$img" \
            ${PUSH:+-Dquarkus.container-image.push=true}
    else
        $MVN -pl eks-pod-identity-crd,eks-d-auth-cli package \
            -Dquarkus.container-image.build=true \
            -Dquarkus.container-image.image="$img" \
            ${PUSH:+-Dquarkus.container-image.push=true}
    fi

    echo -e "${GREEN}cli image: $img${NC}"
}

build_webhook() {
    local img; img=$(image_name "eks-pod-identity-webhook")
    echo -e "${YELLOW}Building eks-pod-identity-webhook ($BUILD_TYPE)...${NC}"

    if [[ "$BUILD_TYPE" == "native" ]]; then
        $MVN -pl eks-pod-identity-webhook package -Pnative \
            -Dquarkus.container-image.build=true \
            -Dquarkus.container-image.image="$img" \
            ${PUSH:+-Dquarkus.container-image.push=true}
    else
        $MVN -pl eks-pod-identity-webhook package \
            -Dquarkus.container-image.build=true \
            -Dquarkus.container-image.image="$img" \
            ${PUSH:+-Dquarkus.container-image.push=true}
    fi

    echo -e "${GREEN}webhook image: $img${NC}"
}

echo -e "${GREEN}=== EKS Auth Build ===${NC}"
echo "target=$TARGET  type=$BUILD_TYPE  push=$PUSH  tag=$TAG"
echo

case "$TARGET" in
    proxy)   build_proxy ;;
    cli)     build_cli ;;
    webhook) build_webhook ;;
    all)     build_proxy; build_cli; build_webhook ;;
    *) echo -e "${RED}Unknown target: $TARGET${NC}"; exit 1 ;;
esac

echo -e "${GREEN}Done.${NC}"
