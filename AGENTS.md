# AGENTS.md

## Directory Overview

Multi-module Quarkus 3.33.1 LTS + Java 25 project. Brings EKS Pod Identity to non-EKS Kubernetes clusters (EKS-D via kubeadm) through a serverless Lambda backend with composable tenant provisioning.

```
eks-dx-credential-service/   # Lambda: credential exchange (hot path, SnapStart)
eks-dx-mgmt-service/         # Lambda: cluster/association CRUD
eks-dx-tenant-service/       # Lambda: tenant provisioning + lifecycle (GraalVM native arm64)
eks-dx-auth-proxy/           # In-cluster proxy: TokenReview fast-fail + Lambda forwarding
eks-dx-pod-identity-webhook/ # Admission webhook: injects env vars + projected token volume
eks-dx-cli/                  # Native CLI (GraalVM): cluster + association + tenant management
eks-dx-model/                # Shared library: TokenClaims record
infra/                       # CDK infrastructure (Java, primary deployment path)
docs/                        # Architecture docs, SSM contract, migration plans
.agents/summary/             # Generated documentation (index.md is the knowledge base entry point)
```

## Key Entry Points

| What | File |
|------|------|
| Credential exchange endpoint | `eks-dx-credential-service/.../resource/EksAuthResource.java` |
| JWT/JWKS validation (5-min cache) | `eks-dx-credential-service/.../service/JwksTokenValidationService.java` |
| Cluster/association CRUD | `eks-dx-mgmt-service/.../resource/ClusterResource.java`, `AssociationResource.java` |
| DynamoDB cluster service | `eks-dx-mgmt-service/.../service/DynamoDbClusterService.java` |
| DynamoDB association service | `eks-dx-mgmt-service/.../service/DynamoDbAssociationService.java` |
| STS AssumeRole | `eks-dx-credential-service/.../service/AwsCredentialService.java` |
| Webhook auth filter | `eks-dx-mgmt-service/.../auth/WebhookAuthFilter.java` |
| Tenant provisioning orchestrator | `eks-dx-tenant-service/.../service/TenantProvisioningService.java` |
| Tenant network (subnets, SG) | `eks-dx-tenant-service/.../service/TenantNetworkService.java` |
| Tenant IAM (role, policies) | `eks-dx-tenant-service/.../service/TenantIamService.java` |
| Tenant EC2 (launch, EIP) | `eks-dx-tenant-service/.../service/TenantEc2Service.java` |
| Tenant DLM (etcd backup) | `eks-dx-tenant-service/.../service/TenantDlmService.java` |
| In-cluster proxy | `eks-dx-auth-proxy/.../resource/EksAuthAgentResource.java` |
| Kubernetes TokenReview | `eks-dx-auth-proxy/.../service/TokenValidationService.java` |
| Pod mutation logic | `eks-dx-pod-identity-webhook/.../PodIdentityMutator.java` |
| CLI entry point | `eks-dx-cli/.../EksDxCommand.java` |
| CLI SigV4 signing | `eks-dx-cli/.../util/AwsSigV4Signer.java` |
| CDK stack | `infra/.../EksDXpressControlPlaneStack.java` |

## Authentication Model

- `POST /clusters/{name}/assets` — no API Gateway auth; token validated by Lambda via JWKS
- Management endpoints — IAM SigV4
- Association GET — open; `WebhookAuthFilter` optionally validates Bearer SA token
- Webhook → Lambda — Bearer SA token with audience `eks-dx.codriverlabs.ai`
- Pod SA tokens — audience `pods.eks.amazonaws.com`

## Repo-Specific Patterns

**Three-Lambda split**: credential-service (SnapStart, 512MB, 30s), mgmt-service (JVM, 256MB, 30s), tenant-service (native arm64, 128MB, 900s). Different scaling profiles.

**Composable tenant provisioning**: `TenantProvisioningService` orchestrates `TenantNetworkService`, `TenantIamService`, `TenantEc2Service`, `TenantDlmService`. Each independently testable.

**SSM as interface**: Infrastructure (Terraform/CDK) writes `/eks-dx/launch-template/{arch}/{pricing}`, `/eks-dx/ami/{arch}/{version}`, `/eks-dx/network/*`. Lambda reads at runtime. Hierarchical paths support `get-parameters-by-path` discovery.

**Per-tenant isolation**: Each tenant gets dedicated subnets (auto-indexed CIDR), security group, IAM role, SQS queue, EventBridge rules, DLM policy.

**DynamoDB key design**: Associations use `PK=CLUSTER#<name>` / `SK=<namespace>#<serviceAccount>`. O(1) GetItem for credential exchange.

**Role naming constraint**: STS AssumeRole scoped to `arn:aws:iam::*:role/eks-dx-pod-*`.

**JWKS caching**: Per `clusterName|audience` in ConcurrentHashMap, 5-minute TTL, per Lambda instance.

**CLI config resolution**: flag → env var → `~/.eks-dx/config` → defaults.

**CDK asset path resolution**: Detects working directory (project root vs `infra/`) to resolve Lambda zip paths correctly for both `mvn exec:java` and `cdk synth`.

**Native binary name**: CLI outputs as `eks-dx` (not `eks-dx-cli`) via `quarkus.native.output-name-prefix`.

## Build

```bash
./build-local.sh              # JVM mode
./build-local.sh --native     # GraalVM native (tenant-service + CLI)
./build-local.sh --skip-tests # Skip unit tests
```

Requires: Java 25, Maven 3.9+, Docker (for native builds via Mandrel container).

## Deploy

```bash
cd infra && cdk deploy EksDXpressControlPlaneStack
```

Requires: SSM parameters written by infrastructure stack first.

## Integration Tests

`DynamoDbIntegrationTest` requires DynamoDB Local on port 18000:
```bash
docker run -d -p 18000:8000 public.ecr.aws/aws-dynamodb-local/aws-dynamodb-local:latest
mvn test -Dintegration.dynamodb=true
```

## Custom Instructions
<!-- This section is for human and agent-maintained operational knowledge.
     Add repo-specific conventions, gotchas, and workflow rules here.
     This section is preserved exactly as-is when re-running codebase-summary. -->
