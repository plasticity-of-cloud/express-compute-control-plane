# Development Guide

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

Available module selectors: `credential`, `mgmt`, `tenant`, `auth-proxy`, `webhook`, `cli`, `cdk`

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
| credential-service | `eks-dx-credential-service/target/function.zip` | Lambda zip (JVM, SnapStart) |
| mgmt-service | `eks-dx-mgmt-service/target/function.zip` | Lambda zip (JVM) |
| tenant-service | `eks-dx-tenant-service/target/function.zip` | Lambda zip (native or JVM) |
| auth-proxy | Docker image `eks-dx-auth-proxy` | In-cluster container |
| webhook | Docker image `eks-dx-pod-identity-webhook` | In-cluster container |
| cli | `eks-dx-cli/target/eks-dx` (native) or `target/*-runner.jar` | CLI binary |

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

## Integration Tests

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
aws logs tail /aws/lambda/eks-d-xpress-tenant-service --follow --region us-east-1
```

### CLI

```bash
# Build native CLI:
./build-local.sh --only cli --native

# Run directly:
./eks-dx-cli/target/eks-dx --help
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
aws logs tail /aws/lambda/eks-d-xpress-tenant-service --follow --region us-east-1

# Tail mgmt-service logs:
aws logs tail /aws/lambda/eks-d-xpress-mgmt-service --follow --region us-east-1
```

### Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Runtime.ExitError` on init | Missing env var or native config issue | Check Lambda env vars match `application.properties` |
| `cannot execute binary file` | Architecture mismatch (built x86, deployed arm64) | Align `--native` host arch with CDK context |
| `NoSuchElementException: null` | Resource lookup returned empty (e.g., missing route table) | Verify shared infra resources exist with expected tags |
| Quarkus config warning about `quarkus.native.*` | Running in JVM mode, native props ignored | Safe to ignore |
