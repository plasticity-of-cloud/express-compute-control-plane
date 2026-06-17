#!/usr/bin/env bash
# build-local.sh — full local build of eks-dx-control-plane
#
# Usage:
#   ./build-local.sh              # Build all modules (JVM mode)
#   ./build-local.sh --native     # GraalVM native for tenant + CLI
#   ./build-local.sh --skip-tests # skip unit tests
#   ./build-local.sh --only tenant-service,cli   # build only specific modules
#
set -euo pipefail

NATIVE=false
SKIP_TESTS=false
ONLY=""

for arg in "$@"; do
  case $arg in
    --help)
      echo "Usage: ./build-local.sh [OPTIONS]"
      echo ""
      echo "Options:"
      echo "  --native       Build GraalVM native binaries for tenant-service and CLI"
      echo "  --skip-tests   Skip unit tests during build"
      echo "  --only <list>  Comma-separated modules to build (skips others)"
      echo "                 Available: credential,mgmt,tenant,auth-proxy,webhook,karpenter,cli,cdk"
      echo "  --help         Show this help message"
      echo ""
      echo "Examples:"
      echo "  ./build-local.sh                           # all modules, JVM"
      echo "  ./build-local.sh --native                  # all modules, native"
      echo "  ./build-local.sh --only tenant             # tenant-service only"
      echo "  ./build-local.sh --only tenant,cli --native"
      echo "  ./build-local.sh --only cdk                # CDK synth only"
      exit 0
      ;;
    --native)     NATIVE=true ;;
    --skip-tests) SKIP_TESTS=true ;;
    --only)       ;; # value captured below
    *)
      # capture value after --only
      if [[ "${PREV_ARG:-}" == "--only" ]]; then
        ONLY="$arg"
      fi
      ;;
  esac
  PREV_ARG="$arg"
done

# Handle --only=value syntax
for arg in "$@"; do
  if [[ "$arg" == --only=* ]]; then
    ONLY="${arg#--only=}"
  fi
done

should_build() {
  [[ -z "$ONLY" ]] || [[ ",$ONLY," == *",$1,"* ]]
}

SKIP_FLAG=""
$SKIP_TESTS && SKIP_FLAG="-DskipTests"

echo "==> Building eks-dx-control-plane (native=${NATIVE}, skipTests=${SKIP_TESTS}, only=${ONLY:-all})"

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
  mvn -B -pl eks-dx-auth-proxy clean package $SKIP_FLAG \
    -Dquarkus.container-image.build=true \
    -Dquarkus.container-image.push=false \
    -Dquarkus.container-image.tag=${IMAGE_TAG}
fi

# 5. Pod identity webhook
if should_build "webhook"; then
  echo "--- pod-identity-webhook"
  mvn -B -pl eks-dx-pod-identity-webhook clean package $SKIP_FLAG \
    -Dquarkus.container-image.build=true \
    -Dquarkus.container-image.push=false \
    -Dquarkus.container-image.tag=${IMAGE_TAG} \
    -Dquarkus.helm.version=${IMAGE_TAG}
fi

# 5b. Karpenter support (EC2NodeClass webhook + ValidationSucceeded controller)
if should_build "karpenter"; then
  echo "--- karpenter-support"
  if $NATIVE; then
    mvn -B -pl eks-dx-karpenter-support clean package $SKIP_FLAG -Pnative \
      -Dquarkus.container-image.build=true \
      -Dquarkus.container-image.push=false \
      -Dquarkus.container-image.tag=${IMAGE_TAG} \
      -Dquarkus.helm.version=${IMAGE_TAG}
  else
    mvn -B -pl eks-dx-karpenter-support clean package $SKIP_FLAG \
      -Dquarkus.container-image.build=true \
      -Dquarkus.container-image.push=false \
      -Dquarkus.container-image.tag=${IMAGE_TAG} \
      -Dquarkus.helm.version=${IMAGE_TAG}
  fi
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
