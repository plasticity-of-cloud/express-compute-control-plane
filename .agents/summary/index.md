# Knowledge Base Index

> **For AI Assistants**: This file is the primary entry point for understanding the EKS-DX Control Plane codebase. Use it to determine which documentation file to consult for specific questions.

## Quick Reference

| Question Type | Consult |
|---------------|---------|
| "How does the system work?" | [architecture.md](architecture.md) |
| "What does module X do?" | [components.md](components.md) |
| "What APIs exist?" / "How do I call X?" | [interfaces.md](interfaces.md) |
| "What's stored in DynamoDB?" | [data_models.md](data_models.md) |
| "How does provisioning work?" / "What happens when..." | [workflows.md](workflows.md) |
| "What libraries are used?" | [dependencies.md](dependencies.md) |
| "What's the tech stack / project info?" | [codebase_info.md](codebase_info.md) |
| "What's missing / needs work?" | [review_notes.md](review_notes.md) |

## Documentation Map

### [codebase_info.md](codebase_info.md)
Project identity, technology stack versions, module inventory, and language breakdown. Start here for factual project metadata.

### [architecture.md](architecture.md)
System-level design: credential exchange flow, architectural layers, design principles (hot-path isolation, composable provisioning, KMS-backed PKI, O(1) lookup), authentication model, and infrastructure patterns. Includes Mermaid diagrams of the overall system.

### [components.md](components.md)
Detailed breakdown of each module's responsibilities, key classes, and runtime characteristics. Covers all 3 Lambda services, 3 in-cluster components, the CLI, and CDK infrastructure. Includes a credential exchange sequence diagram.

### [interfaces.md](interfaces.md)
Complete API reference: REST endpoints (credential exchange, cluster management, association management, tenant lifecycle), Kubernetes webhook interfaces, internal service interfaces (Java method signatures), and CLI command reference.

### [data_models.md](data_models.md)
DynamoDB table schemas (clusters, associations, tenants), domain records (TokenClaims, CallerIdentity, TenantItem), SSM parameter schema, and tenant ID generation algorithm.

### [workflows.md](workflows.md)
Step-by-step process documentation with Mermaid diagrams: credential exchange hot path, managed cluster provisioning, rollback compensation, self-managed registration, cluster deletion, pod identity webhook flow, EC2NodeClass webhook, and stop/resume lifecycle state machine.

### [dependencies.md](dependencies.md)
External dependencies organized by category: framework, AWS SDK modules, security/crypto, Kubernetes, infrastructure, CLI, build tools, test dependencies, and runtime AWS services.

## Key Concepts for Navigation

- **"Hot path"** = credential exchange (credential-service). Latency-critical, SnapStart-optimized.
- **"Control plane"** = management + tenant services. Not latency-critical.
- **"Managed"** = full EC2 provisioning (EKS-D cluster). **"Self-managed"** = BYOK register-only.
- **"TenantNaming"** = single source of truth for all resource name prefixes/patterns.
- **"ProvisionedResources"** = rollback tracker that records what was created during provisioning.
- **"CallerIdentityFilter"** = extracts per-user identity for ownership and quota enforcement.

## File Paths (for code navigation)

The package base is `ai.codriverlabs.eksdx` (or `ai.codriverlabs.karpenter` for karpenter-support, `ai.codriverlabs.eksauth` for auth-proxy, `ai.codriverlabs.webhook` for pod-identity-webhook).

Source paths follow Maven convention: `<module>/src/main/java/ai/codriverlabs/...`
