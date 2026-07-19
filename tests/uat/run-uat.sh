#!/usr/bin/env bash
# run-uat.sh — Build the CLI (if needed) and run Robot Framework UAT suites.
#
# Usage:
#   ./tests/uat/run-uat.sh                     # all mock-mode suites
#   ./tests/uat/run-uat.sh --suite live        # live suites only (requires UAT_LIVE=true)
#   ./tests/uat/run-uat.sh --no-build          # skip CLI build (reuse existing jar)
#
# Environment:
#   UAT_LIVE=true          Enable live tests (default: skipped)
#   AWS_REGION             AWS region (default: us-east-1)
#   UAT_ROLE_ARN           IAM role ARN for live association tests

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/output"

SUITE="mock"
NO_BUILD=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --suite)     SUITE="$2"; shift 2 ;;
        --no-build)  NO_BUILD=true;  shift ;;
        *)           echo "Unknown option: $1"; exit 1 ;;
    esac
done

# ---------------------------------------------------------------------------
# 1. Build CLI (JVM mode) unless --no-build
# ---------------------------------------------------------------------------
if [[ "${NO_BUILD}" == "false" ]]; then
    echo "==> Building CLI (JVM mode)..."
    cd "${PROJECT_ROOT}"
    ./build-local.sh --only cli --skip-tests
fi

# Locate the runner jar
CLI_JAR="$(ls "${PROJECT_ROOT}"/ecp-cli/target/*-runner.jar 2>/dev/null | head -1)"
if [[ -z "${CLI_JAR}" ]]; then
    echo "ERROR: CLI jar not found. Run ./build-local.sh --only cli --skip-tests first."
    exit 1
fi
echo "==> Using CLI jar: ${CLI_JAR}"

# ---------------------------------------------------------------------------
# 2. Install Python dependencies
# ---------------------------------------------------------------------------
echo "==> Installing Python UAT dependencies..."
pip3 install -q -r "${SCRIPT_DIR}/requirements.txt"

# ---------------------------------------------------------------------------
# 3. Determine suite paths
# ---------------------------------------------------------------------------
mkdir -p "${OUTPUT_DIR}"

if [[ "${SUITE}" == "live" ]]; then
    SUITE_PATH="${SCRIPT_DIR}/suites/live"
else
    SUITE_PATH="${SCRIPT_DIR}/suites"
    # Exclude live/ in default mode
    EXCLUDE_LIVE="--exclude live"
fi

# ---------------------------------------------------------------------------
# 4. Run Robot Framework
# ---------------------------------------------------------------------------
echo "==> Running UAT suite: ${SUITE}"
robot \
    --variable CLI_JAR:"${CLI_JAR}" \
    --outputdir "${OUTPUT_DIR}" \
    --loglevel INFO \
    ${EXCLUDE_LIVE:-} \
    "${SUITE_PATH}"

EXIT_CODE=$?

echo ""
echo "==> UAT results: ${OUTPUT_DIR}/report.html"
exit ${EXIT_CODE}
