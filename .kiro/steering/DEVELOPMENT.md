# Development Guide

## Conventions

### File Naming

- All documentation files use **lowercase with hyphens**: `tenant-provisioning.md`, `iam-role-setup.md`
- Exceptions: `README.md`, `LICENSE.md`, `AGENTS.md` (GitHub conventions)
- No `SCREAMING_CASE` filenames — convert `SOME_DOCUMENT.md` → `some-document.md`

### CLI Command Style

- Flat AWS CLI-style: `ecp <verb-noun>` (e.g., `ecp register-cluster`, `ecp create-association`)
- No nested verb/noun commands (removed in v2.2.0)

## Prerequisites

- Java 25 (GraalVM JDK for native builds)
- Maven 3.9+
- Docker (for native builds via Mandrel container)
- AWS CLI + CDK CLI (`npm install -g aws-cdk`)
- AWS credentials with appropriate permissions

## Build — `build-local.sh`

### Full Build

```bash
./build-local.sh                    # All modules, JVM mode
./build-local.sh --native           # All modules, GraalVM native for tenant-service + CLI
./build-local.sh --skip-tests       # Skip unit tests
```

### Selective Build (`--only`)

Build only specific modules (parent POM + model always built for dependency resolution):

```bash
./build-local.sh --only tenant                  # tenant-service only (JVM)
./build-local.sh --only tenant --native         # tenant-service native binary
./build-local.sh --only tenant,cli --native     # tenant + CLI native
./build-local.sh --only credential,mgmt         # both JVM Lambda services
./build-local.sh --only cdk                     # CDK synth validation only
./build-local.sh --only auth-proxy,webhook      # container images only
```

Available module selectors: `credential`, `mgmt`, `tenant`, `auth-proxy`, `webhook`, `cli`, `cdk`, `karpenter`

### Building and Pushing the Karpenter Webhook Image

Read the AWS account ID and region from the current context, then pass them to the build command.
Redirect output to a file (build generates a lot of output) and check failures only on non-zero exit:

```bash
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export AWS_REGION=$(aws configure get region)
./build-local.sh --only karpenter --push --registry $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com > /tmp/karpenter-build.log 2>&1
if [ $? -ne 0 ]; then
    grep -E "ERROR|FAILED|BUILD FAILURE" /tmp/karpenter-build.log
fi
```

### CPU Architecture & Native Builds

The tenant-service and CLI support GraalVM native compilation. The target architecture depends on your build environment:

| Build Host | `--native` produces | Lambda Architecture | CDK Context |
|------------|--------------------|--------------------|-------------|
| arm64 (Graviton/M-series) | arm64 binary | `ARM_64` | (default) |
| x86_64 | x86_64 binary | `X86_64` | `-c nativeArch=x86` |
| Any (JVM mode) | uber-jar | `X86_64` | `-c jvmTenant=true` |

**Important**: The native binary architecture must match the Lambda runtime architecture set in CDK. Use CDK context flags to align:

```bash
# Building on x86_64 host → deploy as x86 native
./build-local.sh --only tenant --native
./deploy-local.sh --context nativeArch=x86

# Building on arm64 host → deploy as arm64 native (default, prod)
./build-local.sh --only tenant --native
./deploy-local.sh

# Skip native entirely → deploy as JVM on x86_64
./build-local.sh --only tenant
./deploy-local.sh --context jvmTenant=true
```

### Build Outputs

| Module | Output | Description |
|--------|--------|-------------|
| credential-service | `ecp-credential-service/target/function.zip` | Lambda zip (JVM, SnapStart) |
| mgmt-service | `ecp-mgmt-service/target/function.zip` | Lambda zip (JVM) |
| tenant-service | `ecp-tenant-service/target/function.zip` | Lambda zip (native or JVM) |
| auth-proxy | Docker image `ecp-auth-proxy` | In-cluster container |
| webhook | Docker image `ecp-workload-identity-webhook` | In-cluster container |
| cli | `ecp-cli/target/ecp` (native) or `target/*-runner.jar` | CLI binary |

## Deploy — `deploy-local.sh`

### Basic Deploy

```bash
./deploy-local.sh                          # Build JVM + deploy
./deploy-local.sh --native                 # Build native + deploy
./deploy-local.sh --skip-build             # Deploy only (reuse existing zips)
```

### Options

```bash
./deploy-local.sh --profile my-profile     # Use specific AWS profile
./deploy-local.sh --context nativeArch=x86 # Deploy x86 native Lambda
./deploy-local.sh --context jvmTenant=true # Deploy JVM mode Lambda
```

### CDK Context Flags

| Flag | Value | Effect |
|------|-------|--------|
| `nativeArch` | `x86` | Deploy tenant-service as x86_64 native binary |
| `jvmTenant` | `true` | Deploy tenant-service as JVM (Java 25 runtime) |
| (neither) | — | Deploy as arm64 native binary (production default) |

### Typical Workflows

**Fast iteration on tenant-service (JVM, no native build):**
```bash
./build-local.sh --only tenant --skip-tests
./deploy-local.sh --skip-build --context jvmTenant=true
```

**Production-like deploy (native arm64, from Graviton host):**
```bash
./build-local.sh --only tenant,credential,mgmt --native
./deploy-local.sh --skip-build
```

**Rebuild and deploy everything:**
```bash
./deploy-local.sh --native
```

## Tenant Management Scripts

```bash
./create_tenant.sh <tenant-id>    # Provision tenant cluster (--wait streams progress)
./delete_tenant.sh <tenant-id>    # Deprovision tenant cluster (--wait polls until gone)
```

DynamoDB integration tests require DynamoDB Local:

```bash
docker run -d -p 18000:8000 public.ecr.aws/aws-dynamodb-local/aws-dynamodb-local:latest
mvn test -Dintegration.dynamodb=true
```

## Module-Specific Development

### Tenant Service (hot path for provisioning)

```bash
# Quick rebuild + redeploy cycle:
./build-local.sh --only tenant --skip-tests
./deploy-local.sh --skip-build --context jvmTenant=true

# Watch logs after invoking:
aws logs tail /aws/lambda/express-compute-tenant-service --follow --region us-east-1
```

### CLI

```bash
# Build native CLI:
./build-local.sh --only cli --native

# Run directly:
./ecp-cli/target/ecp --help
```

### CDK Stack

```bash
# Validate changes without deploying:
./build-local.sh --only cdk

# Full synth:
cd infra && cdk synth

# Diff before deploy:
cd infra && cdk diff
```

## Debugging

### Lambda Logs

```bash
# Tail tenant-service logs:
aws logs tail /aws/lambda/express-compute-tenant-service --follow --region us-east-1

# Tail mgmt-service logs:
aws logs tail /aws/lambda/express-compute-mgmt-service --follow --region us-east-1
```

### Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Runtime.ExitError` on init | Missing env var or native config issue | Check Lambda env vars match `application.properties` |
| `cannot execute binary file` | Architecture mismatch (built x86, deployed arm64) | Align `--native` host arch with CDK context |
| `NoSuchElementException: null` | Resource lookup returned empty (e.g., missing route table) | Verify shared infra resources exist with expected tags |
| Quarkus config warning about `quarkus.native.*` | Running in JVM mode, native props ignored | Safe to ignore |
| `create_tenant.sh` exits immediately with no output | Stream Lambda returns 500 — check logs for `MismatchedInputException` on `ApiGatewayAuthorizerContext.iam` | Ensure `quarkus.lambda-http.enable-security=false` is set in tenant-service `application.properties` |
| Tenant EC2 instance has no public IP | `--eip` not passed and EIP allocation failed | EIP is now always allocated; redeploy tenant-service if using old build |
| Boot script stalls silently after "EKS-D Cluster Setup" | Instance had no internet at boot (no EIP) — first `aws` CLI call timed out | Fixed by always allocating EIP; re-run setup script manually if needed |

## Deploying ecp-karpenter-support to a Tenant Cluster

After making changes, build and push the image + chart:

```bash
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export AWS_REGION=$(aws configure get region)
./build-local.sh --only karpenter --push --registry $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com > /tmp/karpenter-build.log 2>&1
if [ $? -ne 0 ]; then
    grep -E "ERROR|FAILED|BUILD FAILURE" /tmp/karpenter-build.log
fi
```

Then deploy to a running tenant cluster in three steps:

**1. Transfer the chart tarball to the tenant instance:**
```bash
scp -i ~/.express-compute/tenants/us-east-1/<tenant-id>.pem \
  ecp-karpenter-support/target/helm/kubernetes/express-compute-karpenter-support-<version>.tar.gz \
  ec2-user@<tenant-ip>:/opt/eks-d-setup/charts/
```

**2. Uninstall the existing release:**
```bash
ssh -i ~/.express-compute/tenants/us-east-1/<tenant-id>.pem ec2-user@<tenant-ip> \
  "helm uninstall ecp-karpenter-support -n kube-system"
```

**3. Reinstall using the setup script (sources cluster.env automatically):**
```bash
ssh -i ~/.express-compute/tenants/us-east-1/<tenant-id>.pem ec2-user@<tenant-ip> \
  "sudo /opt/eks-d-setup/18-install-ecp-karpenter-support.sh"
```

The script reads env vars from `/opt/eks-d/version.env` and `/opt/eks-d/cluster.env`, picks up the chart from `/opt/eks-d-setup/charts/`, and runs `helm upgrade --install` with the correct cluster identity values.


## Feature and Bug-Fix Workflow

All changes — whether features or bug fixes — follow the same lifecycle. **No exceptions.** The AI assistant (Kiro) must follow this workflow for every code change and must never merge to `main` directly.

### 1. Document first on `main`

Before writing any code, add documentation to the appropriate location:
- New features: create a design doc in `docs/design/` (see naming convention below)
- Bug fixes: add a note to the relevant design doc or create a short doc in `docs/design/fixes/`
- Update `docs/architecture.md` or the relevant user guide if the change is user-facing
- Commit the doc to `main` so the intent is recorded before any implementation diverges

### 2. Create a feature branch

Branch from `main` immediately after the doc commit:
```bash
git checkout -b feature/<short-description>   # new features
git checkout -b fix/<short-description>        # bug fixes
```

For changes spanning multiple repositories, use the **same branch name** across all repos.

### 3. Write tests first (TDD preferred)

- Add unit tests in the relevant module's `src/test/` tree
- Add integration tests where applicable (DynamoDB Local, Quarkus `@QuarkusTest`)
- Add UAT tests if the change is user-facing and testable end-to-end (see `tests/uat/`)
- Tests must cover:
  - Happy path
  - Failure/edge cases introduced by the change
  - State transitions and boundary conditions
  - Authorization and ownership checks where applicable

### 4. Implement the code change

Build and test incrementally:
```bash
./build-local.sh --only <module> --skip-tests  # compile check
mvn test -pl <module>                           # run module tests
```

### 5. Commit incrementally and push to remote

Each logical stepping stone gets its own commit **and push**. Do not accumulate all changes into a single commit. Push after each commit so the remote branch always reflects current progress:

```bash
git add <specific files>
git commit -m "<type>: <concise description>"
git push -u origin feature/<short-description>
```

Commit types: `feat`, `fix`, `test`, `docs`, `refactor`, `chore`

Example commit sequence for a feature:
1. `feat: add progress queue creation and lifecycle`
2. `feat: update IAM policy for SQS access`
3. `feat: rewrite SSE streaming to poll SQS`
4. `test: add unit tests for SQS validation logic`

### 6. Full build must be green — hard gate

Before requesting a PR, **both commands must exit 0**:
```bash
./build-local.sh --skip-tests   # all modules compile
mvn test                         # all tests pass
```

No PR is created if any test fails or the build does not compile cleanly.

### 7. Create PR — never merge directly

```bash
# Push final state
git push origin feature/<short-description>

# Create PR (do NOT merge)
```

**The assistant must stop here and present the PR for human review.** Never merge to `main` automatically. The PR description should include:
- Summary of changes
- What was tested (unit, integration, UAT)
- Any follow-up items or cross-repo dependencies

### 8. Post-merge cleanup

After the human merges the PR:
- Local branch deletion is fine
- Never delete the remote branch from origin — it's the audit trail

---

### Hard Rules for the AI Assistant

| Rule | Rationale |
|------|-----------|
| Never push directly to `main` | All changes go through PR review |
| Never merge a PR autonomously | Human approval required |
| Every code change needs a feature/fix branch | Isolation and traceability |
| Every branch must have passing tests before PR | Build gate is not optional |
| Push to remote after every commit | Remote branch is the audit trail |
| Same branch name across repos for cross-repo changes | Links related work |
| Document first, code second | Intent is recorded before implementation diverges |
| Tests are mandatory, not optional | Unit tests minimum; integration/UAT when achievable |

### Doc naming conventions

- `docs/design/<service-area>/my-feature.md` (lowercase-hyphenated)
- Never `SCREAMING_CASE.md` — convert to lowercase on creation

---

## Java 25 Development Best Practices

- **Constants over inline strings**: All resource name prefixes, path prefixes, and repeated string literals must be declared as `static final` constants in a dedicated class (e.g., `TenantNaming`). Never inline magic strings like `"ecp-tenant-"` directly in business logic.
- **Single source of truth**: If a string is used in more than one place, extract it. If it defines an AWS resource naming convention or IAM policy scope, it belongs in the naming constants class.
- **Method accessors for compound names**: Use static factory methods (e.g., `TenantNaming.roleName(tenantId)`) rather than string concatenation in service code.
- **Naming consistency**: All tenant-scoped AWS resources use `TenantNaming.RESOURCE_PREFIX` (`ecp-tenant-`). Shared infrastructure resources use `express-compute-` (CDK stack-level, not per-tenant).
