#!/usr/bin/env bash
# build-local.sh — full local build of eks-dx-control-plane
#
# Usage:
#   ./build-local.sh              # Build all modules (JVM mode)
#   ./build-local.sh --native     # GraalVM native for tenant + CLI
#   ./build-local.sh --skip-tests # skip unit tests
#   ./build-local.sh --only tenant-service,cli   # build only specific modules
#   ./build-local.sh --push       # push container images after build
#   ./build-local.sh --registry my.registry.io   # private registry for images
#
set -euo pipefail

NATIVE=false
SKIP_TESTS=false
ONLY=""
PUSH=false
REGISTRY=""

for arg in "$@"; do
  case $arg in
    --help)
      echo "Usage: ./build-local.sh [OPTIONS]"
      echo ""
      echo "Options:"
      echo "  --native            Build GraalVM native binaries for tenant-service and CLI"
      echo "  --skip-tests        Skip unit tests during build"
      echo "  --only <list>       Comma-separated modules to build (skips others)"
      echo "                      Available: credential,mgmt,tenant,auth-proxy,webhook,karpenter,cli,cdk"
      echo "  --push              Push container images to registry after build"
      echo "  --registry <url>    Private registry hostname (e.g. 123456789012.dkr.ecr.us-east-1.amazonaws.com)"
      echo "  --help              Show this help message"
      echo ""
      echo "Examples:"
      echo "  ./build-local.sh                           # all modules, JVM"
      echo "  ./build-local.sh --native                  # all modules, native"
      echo "  ./build-local.sh --only tenant             # tenant-service only"
      echo "  ./build-local.sh --only tenant,cli --native"
      echo "  ./build-local.sh --only cdk                # CDK synth only"
      echo "  ./build-local.sh --only auth-proxy,webhook --push --registry my.registry.io"
      exit 0
      ;;
    --native)     NATIVE=true ;;
    --skip-tests) SKIP_TESTS=true ;;
    --push)       PUSH=true ;;
    --only)       ;; # value captured below
    --registry)   ;; # value captured below
    *)
      # capture value after --only / --registry
      if [[ "${PREV_ARG:-}" == "--only" ]]; then
        ONLY="$arg"
      elif [[ "${PREV_ARG:-}" == "--registry" ]]; then
        REGISTRY="$arg"
      fi
      ;;
  esac
  PREV_ARG="$arg"
done

# Handle --only=value and --registry=value syntax
for arg in "$@"; do
  if [[ "$arg" == --only=* ]]; then
    ONLY="${arg#--only=}"
  elif [[ "$arg" == --registry=* ]]; then
    REGISTRY="${arg#--registry=}"
  fi
done

should_build() {
  [[ -z "$ONLY" ]] || [[ ",$ONLY," == *",$1,"* ]]
}

SKIP_FLAG=""
$SKIP_TESTS && SKIP_FLAG="-DskipTests"

# Build Quarkus container-image flags
image_flags() {
  local flags="-Dquarkus.container-image.build=true -Dquarkus.container-image.push=${PUSH} -Dquarkus.container-image.tag=${IMAGE_TAG}"
  [[ -n "$REGISTRY" ]] && flags+=" -Dquarkus.container-image.registry=${REGISTRY}"
  echo "$flags"
}

# Ensure ECR repository exists (create if missing); no-op for non-ECR registries
ensure_ecr_repo() {
  local repo="$1"
  [[ -z "${ECR_REGION:-}" ]] && return
  aws ecr describe-repositories --repository-names "$repo" --region "$ECR_REGION" &>/dev/null \
    || aws ecr create-repository --repository-name "$repo" --region "$ECR_REGION" --image-scanning-configuration scanOnPush=true --query 'repository.repositoryName' --output text
}

echo "==> Building eks-dx-control-plane (native=${NATIVE}, skipTests=${SKIP_TESTS}, only=${ONLY:-all}, push=${PUSH}, registry=${REGISTRY:-default})"

# ECR login if pushing to ECR
if $PUSH && [[ "$REGISTRY" =~ \.dkr\.ecr\.([a-z0-9-]+)\.amazonaws\.com ]]; then
  ECR_REGION="${BASH_REMATCH[1]}"
  echo "==> Authenticating with ECR (${ECR_REGION})"
  aws ecr get-login-password --region "${ECR_REGION}" \
    | docker login --username AWS --password-stdin "${REGISTRY}"
fi

# Resolve version from git tag (strip leading 'v' and any -N-gSHA suffix)
IMAGE_TAG=$(git describe --tags 2>/dev/null | sed 's/^v//;s/-[0-9]*-g[0-9a-f]*$//' || echo "latest")
echo "==> Image tag: ${IMAGE_TAG}"

# 0. Parent POM + model (always — required for dependency resolution)
echo "--- [0] parent pom + model"
mvn -B -N install $SKIP_FLAG
mvn -B -pl eks-dx-model install $SKIP_FLAG

# 1. Credential service
if should_build "credential"; then
  echo "--- credential-service"
  mvn -B -pl eks-dx-credential-service clean package $SKIP_FLAG
fi

# 2. Mgmt service
if should_build "mgmt"; then
  echo "--- mgmt-service"
  mvn -B -pl eks-dx-mgmt-service clean package $SKIP_FLAG
fi

# 3. Tenant service
if should_build "tenant"; then
  echo "--- tenant-service"
  if $NATIVE; then
    mvn -B -pl eks-dx-tenant-service clean package $SKIP_FLAG -Pnative
  else
    mvn -B -pl eks-dx-tenant-service clean package $SKIP_FLAG
  fi
fi

# 4. Auth proxy
if should_build "auth-proxy"; then
  echo "--- auth-proxy"
  ensure_ecr_repo "codriverlabs/eks-d-xpress-auth-proxy"
  mvn -B -pl eks-dx-auth-proxy clean package $SKIP_FLAG $(image_flags)
fi

# 5. Pod identity webhook
if should_build "webhook"; then
  echo "--- pod-identity-webhook"
  ensure_ecr_repo "codriverlabs/eks-d-xpress-pod-identity-webhook"
  mvn -B -pl eks-dx-pod-identity-webhook clean package $SKIP_FLAG $(image_flags) \
    -Dquarkus.helm.version=${IMAGE_TAG}
fi

# 5b. Karpenter support (EC2NodeClass webhook + ValidationSucceeded controller)
if should_build "karpenter"; then
  echo "--- karpenter-support"
  ensure_ecr_repo "plasticity-of-cloud/eks-dx-karpenter-support"
  if $NATIVE; then
    mvn -B -pl eks-dx-karpenter-support clean package $SKIP_FLAG -Pnative $(image_flags) \
      -Dquarkus.helm.version=${IMAGE_TAG}
  else
    mvn -B -pl eks-dx-karpenter-support clean package $SKIP_FLAG $(image_flags) \
      -Dquarkus.helm.version=${IMAGE_TAG}
  fi
  # Patch image tag in generated values.yaml and repack
  CHART_TGZ=$(ls eks-dx-karpenter-support/target/helm/kubernetes/eks-dx-karpenter-support-*.tar.gz | head -1)
  CHART_TMP=$(mktemp -d)
  tar -xzf "$CHART_TGZ" -C "$CHART_TMP"
  sed -i "s/tag: latest/tag: ${IMAGE_TAG}/" "$CHART_TMP/eks-dx-karpenter-support/values.yaml"
  tar -czf "$CHART_TGZ" -C "$CHART_TMP" eks-dx-karpenter-support
  rm -rf "$CHART_TMP"
fi

# 6. CLI
if should_build "cli"; then
  echo "--- cli"
  if $NATIVE; then
    mvn -B -pl eks-dx-cli clean package $SKIP_FLAG -Pnative
  else
    mvn -B -pl eks-dx-cli clean package $SKIP_FLAG
  fi
fi

# 7. CDK
if should_build "cdk"; then
  echo "--- cdk validate"
  mvn -B -pl infra clean compile exec:java
fi

echo ""
echo "==> Build complete"
