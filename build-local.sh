#!/usr/bin/env bash
# build-local.sh — full local build of eks-dx-control-plane
#
# Usage:
#   ./build-local.sh              # JVM mode (fast, x86-safe)
#   ./build-local.sh --native     # GraalVM native for tenant + CLI (requires GraalVM JDK)
#   ./build-local.sh --skip-tests # skip unit tests
#
set -euo pipefail

NATIVE=false
SKIP_TESTS=false

for arg in "$@"; do
  case $arg in
    --native)     NATIVE=true ;;
    --skip-tests) SKIP_TESTS=true ;;
  esac
done

SKIP_FLAG=""
$SKIP_TESTS && SKIP_FLAG="-DskipTests"

echo "==> Building eks-dx-control-plane (native=${NATIVE}, skipTests=${SKIP_TESTS})"

# 0. Parent POM (so child modules can resolve their parent)
echo "--- [0] parent pom"
mvn -B -N install $SKIP_FLAG

# 1. Model (shared, always JVM — install so downstream modules resolve it)
echo "--- [1/7] model"
mvn -B -pl eks-dx-model install $SKIP_FLAG

# 2. Credential service (JVM, SnapStart)
echo "--- [2/7] credential-service"
mvn -B -pl eks-dx-credential-service package $SKIP_FLAG

# 3. Mgmt service (JVM)
echo "--- [3/7] mgmt-service"
mvn -B -pl eks-dx-mgmt-service package $SKIP_FLAG

# 4. Tenant service (native arm64 in prod; JVM or x86 native locally)
echo "--- [4/7] tenant-service"
if $NATIVE; then
  mvn -B -pl eks-dx-tenant-service package $SKIP_FLAG -Pnative
else
  mvn -B -pl eks-dx-tenant-service package $SKIP_FLAG
fi

# 5. Auth proxy (container image, no push)
echo "--- [5/7] auth-proxy"
mvn -B -pl eks-dx-auth-proxy package $SKIP_FLAG \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=false

# 6. Pod identity webhook (container image, no push)
echo "--- [6/7] pod-identity-webhook"
mvn -B -pl eks-dx-pod-identity-webhook package $SKIP_FLAG \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=false

# 7. CLI
echo "--- [7/7] cli"
if $NATIVE; then
  mvn -B -pl eks-dx-cli package $SKIP_FLAG -Pnative
else
  mvn -B -pl eks-dx-cli package $SKIP_FLAG
fi

# 8. CDK synth (validates stack compiles; run `cdk synth` separately for cdk.out/)
echo "--- [8/8] cdk validate"
mvn -B -pl infra compile exec:java

echo ""
echo "==> Build complete"
echo "    Lambda zips:"
echo "      eks-dx-credential-service/target/function.zip"
echo "      eks-dx-mgmt-service/target/function.zip"
echo "      eks-dx-tenant-service/target/function.zip"
if $NATIVE; then
  echo "    CLI binary: eks-dx-cli/target/eks-dx-*-runner"
else
  echo "    CLI jar:    eks-dx-cli/target/eks-dx-cli-*-runner.jar"
fi
echo "    CDK:        cd infra && cdk synth  (to produce cdk.out/)"
