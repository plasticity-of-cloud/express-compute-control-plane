# Codebase Information

## Project Identity

- **Name**: Express Compute Control Plane
- **Group ID**: `ai.codriverlabs`
- **Artifact ID**: `aws-eks-auth-parent`
- **Version**: `1.0.0-SNAPSHOT`
- **License**: Express-Compute Community License

## Technology Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 25 |
| Framework | Quarkus | 3.37.1 |
| AWS SDK | AWS SDK v2 | 2.46.21 |
| Native Build | GraalVM Mandrel | JDK 25 |
| Infrastructure | AWS CDK (Java) | — |
| JWT/JWKS | jose4j | — |
| Kubernetes Client | Fabric8 | — |
| Build Tool | Maven | 3.9+ |
| Container Registry | GHCR | — |
| CI/CD | GitHub Actions | — |

## Module Inventory

| Module | Type | Runtime | Description |
|--------|------|---------|-------------|
| `ecp-model` | Library | — | Shared: `TokenClaims`, `CallerIdentity` |
| `ecp-credential-service` | Lambda | JVM (SnapStart, 512MB) | Hot-path credential exchange |
| `ecp-mgmt-service` | Lambda | JVM (256MB) | Cluster/association CRUD |
| `ecp-tenant-service` | Lambda | Native arm64 or JVM (128MB, 15min) | Tenant lifecycle orchestrator |
| `ecp-auth-proxy` | Container | JVM | In-cluster TokenReview + forwarding |
| `ecp-workload-identity-webhook` | Container | JVM | Pod mutation admission webhook |
| `ecp-karpenter-support` | Container | JVM | EC2NodeClass webhook + reconciler |
| `ecp-cli` | CLI | Native (GraalVM) | `ecp` command-line tool |
| `infra` | CDK | JVM | CloudFormation stack definition |

## Programming Languages

| Language | Usage |
|----------|-------|
| Java 25 | All application modules (primary) |
| Shell (bash) | Build scripts, setup scripts, deployment |
| Python | UAT mock server + Robot Framework libraries |
| YAML | Helm charts, GitHub Actions, K8s manifests |

## Repository Statistics

- **Total files**: ~7,400
- **Source modules**: 9
- **Key source files**: 134 prioritized
- **Design documents**: 20+
- **CI Workflows**: 3 (ci.yml, release.yml, deploy-lambda.yml)
