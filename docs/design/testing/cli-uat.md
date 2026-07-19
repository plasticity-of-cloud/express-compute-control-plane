# CLI UAT — Robot Framework Test Suite Design

## Overview

A Robot Framework / Python 3 UAT suite that verifies every `ecp` CLI command
from the user's perspective: correct exit codes, stdout/stderr content, file side
effects, and error messages.

Two execution modes:

| Mode | Trigger | Infrastructure needed |
|------|---------|----------------------|
| **Mock** (default) | `./run-uat.sh` | None — Python mock HTTP server only |
| **Live** | `UAT_LIVE=true ./run-uat.sh` | Deployed AWS stack + valid credentials |

Mock-mode tests run in CI with no AWS credentials and complete in under 30 seconds.
Live-mode tests are gated behind `UAT_LIVE=true` and cover the full provisioning
lifecycle.

---

## Directory structure

```
tests/uat/
├── requirements.txt              # robotframework, requests
├── run-uat.sh                    # entry point (builds CLI, runs suites)
├── README.md
├── mock_server/
│   ├── __init__.py
│   └── server.py                 # Threaded mock HTTP server + request recorder
├── libraries/
│   └── MockServerLibrary.py      # Robot Framework library wrapping mock_server
├── resources/
│   ├── common.resource           # CLI path, port variables, shared keywords
│   └── mock_server.resource      # Start/Stop/Configure server keywords
└── suites/
    ├── 01_help_and_version.robot
    ├── 02_configure.robot
    ├── 03_create_cluster.robot   # validation + 409 + happy path (mock)
    ├── 04_get_cluster_access.robot
    ├── 05_list_describe_cluster.robot
    ├── 06_stop_resume_cluster.robot
    ├── 07_associations.robot
    └── live/
        ├── README.md             # instructions for running live tests
        └── 01_full_lifecycle.robot
```

---

## Mock server design

`MockServerLibrary` is a Python class used as a Robot Framework library.
It starts a `http.server.HTTPServer` in a daemon thread on a random free port
and records every request for assertion.

```python
class MockServerLibrary:
    def start_mock_server(self) -> int          # returns port
    def stop_mock_server(self)
    def set_response(self, method, path_pattern, status_code, body)
    def get_request_count(self, method, path_pattern) -> int
    def get_last_request_body(self, method, path_pattern) -> str
    def clear_requests(self)
    def reset_responses(self)
```

The CLI reads its endpoint from environment variables:
- `ECP_PROVISIONING_URL` — provisioning Function URL (tenant-service)
- `ECP_ENDPOINT` — management API Gateway endpoint

Both are pointed at `http://localhost:{port}` in mock mode.

---

## What each suite tests

### 01_help_and_version.robot
- `ecp --help` exits 0 and lists all subcommands
- `ecp --version` exits 0
- `ecp unknown-command` exits non-zero with usage hint
- Each subcommand `--help` exits 0

### 02_configure.robot
- `ecp configure` writes `~/.express-compute/config`
- Subsequent reads resolve the configured endpoint
- Missing endpoint → actionable error

### 03_create_cluster.robot
**Validation (no server needed):**
- Missing cluster name → 400 error, exit 1
- Name starting with digit → 400, exit 1
- Name with underscore → 400, exit 1
- `--arch invalid` → 400, exit 1

**Mock server responses:**
- Server returns 201 → success message, exit 0
- Server returns 409 `ResourceInUseException` → `Error: Cluster 'X' already exists...`, exit 1
- Server returns 429 `QuotaExceededException` → quota message, exit 1
- Server returns 500 → `Error: Internal server error`, exit 1
- `--output json` → valid JSON on stdout

**Self-managed mode:**
- `--jwks-file` without `--issuer` → validation error, exit 1
- `--issuer` without `--jwks-file`/`--jwks-uri` → validation error, exit 1
- With valid `--jwks-file` + `--issuer` → correct POST body sent to mock server

### 04_get_cluster_access.robot
**Mock server responses:**
- Cluster `state=ready`, `managed=true`, `publicIp` present → prints IP + ssh command, exit 0
- `managed=false` → `Error: ... self-managed ...`, exit 1
- `state=stopped` → `Error: ... stopped. Resume it first ...`, exit 1
- `state=hibernating` → same as stopped
- `state=provisioning` → `Error: ... not ready yet ...`, exit 1
- `publicIp` absent → `Error: ... no public IP ...`, exit 1
- Server returns 404 → `Error: cluster 'X' not found`, exit 1
- `--output json` → valid JSON with `publicIp`, `sshCommand` fields
- `--save-key` → Secrets Manager call is made (mock), `.pem` file written with mode 600

### 05_list_describe_cluster.robot
- `list-clusters` → table output, exit 0
- `list-clusters --output json` → valid JSON array
- `describe-cluster <name>` → shows name, issuer, createdAt
- `describe-cluster nonexistent` → 404 → exit 1

### 06_stop_resume_cluster.robot
- `stop-cluster <name>` → mock 202 → success message
- `resume-cluster <name>` → mock 202 → success message
- Both on 404 → exit 1

### 07_associations.robot
- `create-association` missing args → exit 1
- `create-association` valid args → POST sent with correct body
- `list-associations <cluster>` → table output
- `delete-association <cluster> <id>` → 204 → exit 0
- `delete-association` 404 → exit 1

### live/01_full_lifecycle.robot (UAT_LIVE=true only)
```
Given a deployed stack and valid credentials
When I run: ecp create-cluster uat-test --wait
Then the cluster reaches state=ready within 15 minutes
And ecp get-cluster-access uat-test prints a public IP
And the .pem file exists locally with mode 600
And SSH connection succeeds

When I run: ecp stop-cluster uat-test
Then state transitions to stopped within 5 minutes

When I run: ecp resume-cluster uat-test
Then state transitions to ready within 5 minutes

When I run: ecp create-association uat-test default my-sa <role-arn>
Then ecp list-associations uat-test shows the association

When I run: ecp delete-cluster uat-test
Then the cluster is removed from list-clusters output
```

---

## Running

```bash
# Install dependencies
pip3 install -r tests/uat/requirements.txt

# Build CLI (JVM mode, no native required)
./build-local.sh --only cli --skip-tests

# Run all mock-mode suites
./tests/uat/run-uat.sh

# Run a single suite
robot --variable CLI_JAR:$(ls ecp-cli/target/*-runner.jar) \
  tests/uat/suites/03_create_cluster.robot

# Run live tests (requires deployed stack + AWS credentials)
UAT_LIVE=true AWS_REGION=us-east-1 ./tests/uat/run-uat.sh
```

---

## CI integration

Add to `.github/workflows/` (or equivalent):

```yaml
- name: Build CLI
  run: ./build-local.sh --only cli --skip-tests

- name: Run UAT (mock mode)
  run: |
    pip3 install -r tests/uat/requirements.txt
    ./tests/uat/run-uat.sh

- name: Upload UAT report
  uses: actions/upload-artifact@v4
  with:
    name: uat-report
    path: tests/uat/output/
```

---

## Dependencies

```
robotframework>=7.0
requests>=2.31
```

No additional test infrastructure required for mock mode — the mock server is
pure Python stdlib (`http.server`, `threading`, `socketserver`).
