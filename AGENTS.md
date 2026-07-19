# AGENTS.md

## Directory Overview

Multi-module Quarkus 3.37+ / Java 25 project. Brings EKS Workload Identity to non-EKS Kubernetes clusters (EKS-D, k3s, microk8s) through a serverless Lambda backend with KMS-backed PKI, composable tenant provisioning, and compensating rollback.

```
ecp-credential-service/   # Lambda: credential exchange (hot path, SnapStart)
ecp-mgmt-service/         # Lambda: cluster/association CRUD, JWKS refresh
ecp-tenant-service/       # Lambda: cluster lifecycle + provisioning (JVM or native arm64)
ecp-auth-proxy/           # In-cluster proxy: TokenReview fast-fail + Lambda forwarding
ecp-workload-identity-webhook/ # Admission webhook: injects env vars + projected token volume
ecp-karpenter-support/    # EC2NodeClass webhook + ValidationSucceeded reconciler
ecp-cli/                  # Native CLI: create-cluster, delete-cluster, associations
ecp-model/                # Shared library: TokenClaims, CallerIdentity
infra/                       # CDK infrastructure (Java, primary deployment path)
docs/                        # Architecture docs, design, roadmap, user guides
.agents/summary/             # Generated documentation (index.md is the knowledge base entry point)
.kiro/steering/              # Kiro steering documents (DEVELOPMENT.md, CI_CD_INTEGRATION.md)
```

## Key Entry Points

| What | File |
|------|------|
| Cluster create/delete (unified) | `ecp-tenant-service/.../resource/ClusterResource.java` |
| Tenant provisioning orchestrator | `ecp-tenant-service/.../service/TenantProvisioningService.java` |
| PKI generation (KMS-signed CA) | `ecp-tenant-service/.../service/TenantCryptoService.java` |
| Resource naming constants | `ecp-tenant-service/.../TenantNaming.java` |
| Credential exchange endpoint | `ecp-credential-service/.../resource/CredentialExchangeResource.java` |
| Trust policy management | `ecp-mgmt-service/.../service/TrustPolicyService.java` |
| CLI entry point | `ecp-cli/.../EcpCommand.java` |
| CLI unified create-cluster | `ecp-cli/.../cluster/UnifiedCreateClusterCommand.java` |
| CDK stack | `infra/.../ExpressComputeControlPlaneStack.java` |
| In-cluster proxy | `ecp-auth-proxy/.../resource/AuthProxyResource.java` |
| Pod mutation logic | `ecp-workload-identity-webhook/.../WorkloadIdentityMutator.java` |
| Karpenter EC2NodeClass webhook | `ecp-karpenter-support/.../resource/Ec2NodeClassWebhookResource.java` |
| Karpenter reconciler | `ecp-karpenter-support/.../reconciler/Ec2NodeClassReconciler.java` |

## Authentication Model

- `POST /clusters/{name}/assets` — no API Gateway auth; token validated by Lambda via JWKS
- Tenant-service (Function URL) — IAM SigV4
- Management endpoints (API Gateway) — IAM SigV4
- Pod SA tokens — audience `pods.eks.amazonaws.com`

## Repo-Specific Patterns

**Unified cluster lifecycle**: `POST /clusters` on tenant-service handles both managed (full provisioning) and self-managed (register only). Server infers mode from request body: `jwks` present → self-managed, absent → managed.

**TenantNaming constants**: All tenant-scoped resource names go through `TenantNaming` class. `RESOURCE_PREFIX = "ecp-tenant-"`, `SECRET_PREFIX = "ecp/tenant/"`. Never inline these strings.

**KMS-backed PKI**: `TenantCryptoService` generates CA + SA keys before EC2 launch. CA cert signed via shared KMS asymmetric key (`kms:Sign`). JWKS pre-registered in DynamoDB — no post-boot registration needed.

**Composable provisioning with rollback**: `TenantProvisioningService` orchestrates network, crypto, IAM, SQS, DLM, EC2. `ProvisionedResources` tracks what was created; on failure, `rollback()` cleans up in reverse order.

**Network internal cleanup**: `TenantNetworkService.createTenantNetwork()` wraps subnet/SG creation in try-catch and deletes partially-created resources before re-throwing.

**DLM correct teardown order**: Snapshots first (tagged `ecp-tenant`), then policy (by tag lookup), then IAM role.

**CLI positional cluster name**: `ecp create-cluster my-cluster --wait` (not `--name`).

**Per-user identity**: `CallerIdentityFilter` extracts `idcUserId` from assumed-role session name (IAM Identity Center email).

**DynamoDB key design**: Associations use `PK=CLUSTER#<name>` / `SK=<namespace>#<serviceAccount>`. O(1) GetItem for credential exchange.

**Shared infra naming**: Route tables are `ecp-infra-public-rt` / `ecp-infra-private-rt`. Platform tag for shared infra: `Platform=express-compute`. Per-tenant resources use `ecp-tenant-` prefix.

**Karpenter webhook patterns**: `Ec2NodeClassWebhookResource` rewrites `amiFamily` to `Custom`, injects cluster-specific userData (MIME merge for AL2023/Bottlerocket), and adds required tags. Idempotent — skips mutation when already applied. `Ec2NodeClassReconciler` sets `ValidationSucceeded` status condition using generation-based conflict detection.

## CI/CD

- **CI** (`ci.yml`): Push/PR to main. Temurin Java 25, `mvn verify`, CDK synth, container image builds (no push), Helm lint. Integration tests with DynamoDB Local.
- **Release** (`release.yml`): Triggered by `v*` tags. Multi-arch matrix (amd64 + arm64). Native CLI binaries, Lambda zips, container images pushed to GHCR.
- **Deploy Lambda** (`deploy-lambda.yml`): Targeted Lambda deployment.

## Build

```bash
./build-local.sh                           # All modules, JVM
./build-local.sh --native                  # GraalVM native (tenant + CLI)
./build-local.sh --only tenant --native    # Single module
./build-local.sh --only tenant,cli --native --skip-tests
```

Modules: `credential`, `mgmt`, `tenant`, `auth-proxy`, `webhook`, `cli`, `cdk`, `karpenter`

## Deploy

```bash
./deploy-local.sh                          # Build + deploy
./deploy-local.sh --skip-build             # Reuse existing zips
./deploy-local.sh --context jvmTenant=true # JVM mode (fast iteration)
```

## Integration Tests

```bash
docker run -d -p 18000:8000 public.ecr.aws/aws-dynamodb-local/aws-dynamodb-local:latest
mvn test -Dintegration.dynamodb=true
```

## Custom Instructions
<!-- This section is for human and agent-maintained operational knowledge.
     Add repo-specific conventions, gotchas, and workflow rules here.
     This section is preserved exactly as-is when re-running codebase-summary. -->
