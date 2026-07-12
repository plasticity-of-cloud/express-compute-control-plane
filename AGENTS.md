# AGENTS.md

## Directory Overview

Multi-module Quarkus 3.37+ / Java 25 project. Brings EKS Pod Identity to non-EKS Kubernetes clusters (EKS-D, k3s, microk8s) through a serverless Lambda backend with KMS-backed PKI, composable tenant provisioning, and compensating rollback.

```
eks-dx-credential-service/   # Lambda: credential exchange (hot path, SnapStart)
eks-dx-mgmt-service/         # Lambda: cluster/association CRUD, JWKS refresh
eks-dx-tenant-service/       # Lambda: cluster lifecycle + provisioning (JVM or native arm64)
eks-dx-auth-proxy/           # In-cluster proxy: TokenReview fast-fail + Lambda forwarding
eks-dx-pod-identity-webhook/ # Admission webhook: injects env vars + projected token volume
eks-dx-karpenter-support/    # EC2NodeClass webhook + ValidationSucceeded reconciler
eks-dx-cli/                  # Native CLI: create-cluster, delete-cluster, associations
eks-dx-model/                # Shared library: TokenClaims, CallerIdentity
infra/                       # CDK infrastructure (Java, primary deployment path)
docs/                        # Architecture docs, design, roadmap, user guides
.agents/summary/             # Generated documentation (index.md is the knowledge base entry point)
.kiro/steering/              # Kiro steering documents (DEVELOPMENT.md, CI_CD_INTEGRATION.md)
```

## Key Entry Points

| What | File |
|------|------|
| Cluster create/delete (unified) | `eks-dx-tenant-service/.../resource/ClusterResource.java` |
| Tenant provisioning orchestrator | `eks-dx-tenant-service/.../service/TenantProvisioningService.java` |
| PKI generation (KMS-signed CA) | `eks-dx-tenant-service/.../service/TenantCryptoService.java` |
| Resource naming constants | `eks-dx-tenant-service/.../TenantNaming.java` |
| Credential exchange endpoint | `eks-dx-credential-service/.../resource/EksAuthResource.java` |
| Trust policy management | `eks-dx-mgmt-service/.../service/TrustPolicyService.java` |
| CLI entry point | `eks-dx-cli/.../EksDxCommand.java` |
| CLI unified create-cluster | `eks-dx-cli/.../cluster/UnifiedCreateClusterCommand.java` |
| CDK stack | `infra/.../EksDXpressControlPlaneStack.java` |
| In-cluster proxy | `eks-dx-auth-proxy/.../resource/EksAuthAgentResource.java` |
| Pod mutation logic | `eks-dx-pod-identity-webhook/.../PodIdentityMutator.java` |
| Karpenter EC2NodeClass webhook | `eks-dx-karpenter-support/.../resource/Ec2NodeClassWebhookResource.java` |
| Karpenter reconciler | `eks-dx-karpenter-support/.../reconciler/Ec2NodeClassReconciler.java` |

## Authentication Model

- `POST /clusters/{name}/assets` — no API Gateway auth; token validated by Lambda via JWKS
- Tenant-service (Function URL) — IAM SigV4
- Management endpoints (API Gateway) — IAM SigV4
- Pod SA tokens — audience `pods.eks.amazonaws.com`

## Repo-Specific Patterns

**Unified cluster lifecycle**: `POST /clusters` on tenant-service handles both managed (full provisioning) and self-managed (register only). Server infers mode from request body: `jwks` present → self-managed, absent → managed.

**TenantNaming constants**: All tenant-scoped resource names go through `TenantNaming` class. `RESOURCE_PREFIX = "eks-dx-tenant-"`, `SECRET_PREFIX = "eks-dx/tenant/"`. Never inline these strings.

**KMS-backed PKI**: `TenantCryptoService` generates CA + SA keys before EC2 launch. CA cert signed via shared KMS asymmetric key (`kms:Sign`). JWKS pre-registered in DynamoDB — no post-boot registration needed.

**Composable provisioning with rollback**: `TenantProvisioningService` orchestrates network, crypto, IAM, SQS, DLM, EC2. `ProvisionedResources` tracks what was created; on failure, `rollback()` cleans up in reverse order.

**Network internal cleanup**: `TenantNetworkService.createTenantNetwork()` wraps subnet/SG creation in try-catch and deletes partially-created resources before re-throwing.

**DLM correct teardown order**: Snapshots first (tagged `eks-dx-tenant`), then policy (by tag lookup), then IAM role.

**CLI positional cluster name**: `eks-dx create-cluster my-cluster --wait` (not `--name`).

**Per-user identity**: `CallerIdentityFilter` extracts `idcUserId` from assumed-role session name (IAM Identity Center email).

**DynamoDB key design**: Associations use `PK=CLUSTER#<name>` / `SK=<namespace>#<serviceAccount>`. O(1) GetItem for credential exchange.

**Shared infra naming**: Route tables are `eks-dx-infra-public-rt` / `eks-dx-infra-private-rt`. Platform tag for shared infra: `Platform=eks-d-xpress`. Per-tenant resources use `eks-dx-tenant-` prefix.

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
