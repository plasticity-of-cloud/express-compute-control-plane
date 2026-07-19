#!/usr/bin/env bash
# deploy-local.sh — build Lambda zips and deploy CDK stack
#
# Usage:
#   ./deploy-local.sh                  # build JVM + deploy
#   ./deploy-local.sh --skip-build     # deploy only (reuse existing zips)
#   ./deploy-local.sh --native         # build GraalVM native tenant-service + deploy
#   ./deploy-local.sh --profile <name> # AWS profile to use
#   ./deploy-local.sh --context key=val # extra CDK context (repeatable)
#
set -euo pipefail

SKIP_BUILD=false
NATIVE=false
AWS_PROFILE_ARG=""
CDK_CONTEXT_ARGS=""

# Auto-detect host architecture for native builds
HOST_ARCH=$(uname -m)
if [[ "$HOST_ARCH" == "x86_64" ]]; then
  NATIVE_ARCH_CONTEXT="-c nativeArch=x86"
else
  NATIVE_ARCH_CONTEXT=""
fi

while [[ $# -gt 0 ]]; do
  case $1 in
    --help)
      echo "Usage: ./deploy-local.sh [OPTIONS]"
      echo ""
      echo "Options:"
      echo "  --skip-build         Skip Maven build and reuse existing Lambda zips"
      echo "  --native             Build GraalVM native tenant-service before deploying"
      echo "  --profile <name>     AWS CLI profile to use for CDK deployment"
      echo "  --context key=val    Extra CDK context variable (repeatable)"
      echo "  --help               Show this help message"
      echo ""
      echo "Examples:"
      echo "  ./deploy-local.sh                          # build JVM + deploy"
      echo "  ./deploy-local.sh --skip-build             # deploy only (reuse existing zips)"
      echo "  ./deploy-local.sh --native                 # native build + deploy"
      echo "  ./deploy-local.sh --profile my-profile     # use specific AWS profile"
      echo "  ./deploy-local.sh --context jvmTenant=true # deploy tenant-service in JVM mode"
      echo "  ./deploy-local.sh --context nativeArch=x86 # deploy x86 native Lambda"
      echo "  ./deploy-local.sh --context env=staging -c region=us-west-2"
      exit 0
      ;;
    --skip-build) SKIP_BUILD=true ;;
    --native)     NATIVE=true ;;
    --profile)    AWS_PROFILE_ARG="--profile $2"; shift ;;
    --context)    CDK_CONTEXT_ARGS="$CDK_CONTEXT_ARGS -c $2"; shift ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done

if ! $SKIP_BUILD; then
  echo "==> Building Lambda zips"
  mvn -B -N install -DskipTests

  mvn -B -pl ecp-model install -DskipTests
  mvn -B -pl ecp-credential-service package -DskipTests
  mvn -B -pl ecp-mgmt-service package -DskipTests

  if $NATIVE; then
    mvn -B -pl ecp-tenant-service package -DskipTests -Pnative
  else
    mvn -B -pl ecp-tenant-service package -DskipTests
  fi
  echo "==> Build complete"
fi

echo "==> Deploying CDK stack"
# Auto-pass architecture context when deploying native builds
ARCH_CONTEXT=""
if $NATIVE; then
  ARCH_CONTEXT="$NATIVE_ARCH_CONTEXT"
  echo "    Host arch: $HOST_ARCH → ${NATIVE_ARCH_CONTEXT:-arm64 (default)}"
fi

cd infra
rm -rf cdk.out
cdk synth --context development=true
cdk deploy ExpressComputeControlPlaneStack \
  --require-approval never \
  --context development=true \
  $AWS_PROFILE_ARG \
  $ARCH_CONTEXT \
  $CDK_CONTEXT_ARGS

echo ""
echo "==> Deploy complete"

echo "Removing configuration file $HOME/.express-compute/config, as URL changed" 
rm -rf $HOME/.express-compute/config
