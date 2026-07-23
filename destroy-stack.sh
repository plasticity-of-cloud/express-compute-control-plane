#!/usr/bin/env bash
# destroy-local.sh — destroy the CDK stack
#
# Usage:
#   ./destroy-local.sh                  # destroy with confirmation prompt
#   ./destroy-local.sh --force          # skip confirmation
#   ./destroy-local.sh --profile <name> # AWS profile to use
#
set -euo pipefail

FORCE=false
AWS_PROFILE_ARG=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --force)   FORCE=true ;;
    --profile) AWS_PROFILE_ARG="--profile $2"; shift ;;
    --help)
      echo "Usage: ./destroy-local.sh [--force] [--profile <name>]"
      exit 0
      ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done

if ! $FORCE; then
  read -r -p "Destroy ECPpressControlPlaneStack? This is irreversible. [y/N] " confirm
  [[ "$confirm" =~ ^[yY]$ ]] || { echo "Aborted."; exit 0; }
fi

echo "==> Destroying CDK stack"
cd infra
cdk destroy ExpressComputeControlPlaneStack --force $AWS_PROFILE_ARG

echo "==> Stack destroyed"
