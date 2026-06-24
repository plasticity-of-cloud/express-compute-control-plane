# CLI Command Semantics Migration

**Date:** 2026-06-24
**Status:** Approved for v2.2.0
**Breaking:** Yes (v2.1.0 commands deprecated, removed in v3.0.0)

## Problem

The current CLI uses kubectl-style verb-first nesting (`eks-dx create cluster`). This creates:

1. **Ambiguity between `create cluster` and `create tenant`** — "create cluster" implies infrastructure provisioning, but it actually just *registers* an existing cluster's OIDC/JWKS. `create tenant` is the real provisioning command.
2. **Inconsistency with AWS CLI** — users familiar with `aws eks create-pod-identity-association` expect `eks-dx create-pod-identity-association` (flat, hyphenated).
3. **Verb collision** — `create` means different things: register (cluster), provision (tenant), bind (association).

## Decision

Migrate to **flat AWS CLI-style** with semantic verbs that clarify intent.

## Command Mapping

### Current → New

| Current (v2.1.0) | New (v2.2.0) | Rationale |
|-------------------|--------------|-----------|
| `eks-dx create cluster` | `eks-dx register-cluster` | Registers an external cluster (OIDC/JWKS). Not provisioning. |
| `eks-dx describe cluster` | `eks-dx describe-cluster` | Mirrors `aws eks describe-cluster` |
| `eks-dx list clusters` | `eks-dx list-clusters` | Mirrors `aws eks list-clusters` |
| `eks-dx update cluster` | `eks-dx update-cluster` | Mirrors `aws eks update-cluster-config` |
| `eks-dx delete cluster` | `eks-dx deregister-cluster` | Removes registration. Doesn't destroy infra. |
| `eks-dx create pod-identity-association` | `eks-dx create-association` | Short, unambiguous (only one association type) |
| `eks-dx describe pod-identity-association` | `eks-dx describe-association` | |
| `eks-dx list pod-identity-associations` | `eks-dx list-associations` | |
| `eks-dx delete pod-identity-association` | `eks-dx delete-association` | |
| `eks-dx create tenant` | `eks-dx create-tenant` | Flat. Actual infra provisioning. |
| `eks-dx delete tenant` | `eks-dx delete-tenant` | Flat. Actual infra destruction. |
| `eks-dx stop tenant` | `eks-dx stop-tenant` | |
| `eks-dx resume tenant` | `eks-dx resume-tenant` | |
| `eks-dx configure` | `eks-dx configure` | Unchanged (already flat) |

### AWS EKS CLI parity

| `aws eks` command | `eks-dx` equivalent | Notes |
|-------------------|--------------------|----|
| `aws eks create-cluster` | `eks-dx create-tenant` | EKS provisions infra; so does create-tenant |
| `aws eks delete-cluster` | `eks-dx delete-tenant` | |
| `aws eks describe-cluster` | `eks-dx describe-cluster` | Returns registered cluster info (not tenant infra) |
| `aws eks list-clusters` | `eks-dx list-clusters` | |
| `aws eks create-pod-identity-association` | `eks-dx create-association` | Shortened — unambiguous in eks-dx context |
| `aws eks delete-pod-identity-association` | `eks-dx delete-association` | |
| `aws eks list-pod-identity-associations` | `eks-dx list-associations` | |
| `aws eks describe-pod-identity-association` | `eks-dx describe-association` | |
| (no equivalent) | `eks-dx register-cluster` | EKS doesn't need this (it owns the cluster) |
| (no equivalent) | `eks-dx deregister-cluster` | |
| (no equivalent) | `eks-dx stop-tenant` | EKS doesn't have hibernate |
| (no equivalent) | `eks-dx resume-tenant` | |

### Why `register-cluster` not `create-cluster`

| Verb | Semantics | Infrastructure? |
|------|-----------|-----------------|
| `create-tenant` | Provisions EC2, network, IAM, bootstraps K8s | **Yes** — creates real resources |
| `register-cluster` | Records OIDC issuer + JWKS in DynamoDB | **No** — metadata only |
| `deregister-cluster` | Removes DynamoDB entry | **No** — metadata only |

Using `create-cluster` for a metadata registration is misleading. Users would expect infrastructure (like `aws eks create-cluster` which provisions a control plane). `register-cluster` makes it clear this is a lightweight binding operation for externally-managed clusters (k3s, microk8s, Rancher, etc.).

For EKS-D-Xpress tenants, the cluster is registered automatically during `create-tenant` — users never call `register-cluster` manually.

### Why `create-association` not `create-pod-identity-association`

- EKS-DX is exclusively a Pod Identity service — there's no other type of association
- `association` is unambiguous in context
- Saves 21 characters in every invocation
- Alias `create-pod-identity-association` provided for script compatibility

## Aliases (backward compatibility)

During v2.2.0, old commands work but print a deprecation warning:

```
$ eks-dx create cluster --name foo
⚠ DEPRECATED: 'eks-dx create cluster' is deprecated. Use 'eks-dx register-cluster' instead.
  (old syntax will be removed in v3.0.0)

✓ Cluster registered: foo
```

Aliases to maintain:
- `create cluster` → `register-cluster`
- `delete cluster` → `deregister-cluster`
- `create pod-identity-association` → `create-association`
- `create-pod-identity-association` → `create-association` (for AWS CLI muscle memory)
- All other old forms → flat equivalents

## Complete v2.2.0 Command Set

```
eks-dx configure                  # Set endpoint, region, profile
eks-dx register-cluster           # Register external cluster (OIDC/JWKS)
eks-dx deregister-cluster         # Remove cluster registration
eks-dx describe-cluster           # Show cluster details
eks-dx list-clusters              # List registered clusters
eks-dx update-cluster             # Refresh JWKS / update issuer
eks-dx create-association         # Create pod identity association
eks-dx delete-association         # Delete pod identity association
eks-dx describe-association       # Show association details
eks-dx list-associations          # List associations for a cluster
eks-dx create-tenant              # Provision EKS-D-Xpress cluster
eks-dx delete-tenant              # Deprovision tenant
eks-dx describe-tenant            # Show tenant state
eks-dx stop-tenant                # Hibernate (stop EC2, preserve EBS)
eks-dx resume-tenant              # Resume hibernated tenant
eks-dx version                    # Show CLI version
```

## Implementation Plan

### Phase 1 — Add flat commands (non-breaking)

1. Create flat command classes (e.g., `RegisterClusterCommand` with `@Command(name = "register-cluster")`)
2. Each flat command delegates to existing logic (or is the same class with two names)
3. Update `EksDxCommand` subcommands list to include both old and new
4. Old commands continue to work, no deprecation warning yet
5. Update all documentation to use new command names

### Phase 2 — Deprecation warnings

1. Old nested commands print `⚠ DEPRECATED` to stderr
2. Documentation only shows new commands
3. Release notes announce deprecation timeline

### Phase 3 — Remove old commands (v3.0.0)

1. Remove `CreateCommand`, `DeleteCommand`, `ListCommand`, `DescribeCommand`, `UpdateCommand`, `StopCommand`, `ResumeCommand` (the verb-grouping classes)
2. Remove old subcommand classes or consolidate
3. Only flat commands remain

## Picocli Implementation Notes

Picocli supports flat subcommands directly on the top-level command:

```java
@TopCommand
@Command(name = "eks-dx", subcommands = {
    ConfigureCommand.class,          // configure
    RegisterClusterCommand.class,    // register-cluster
    DeregisterClusterCommand.class,  // deregister-cluster
    DescribeClusterCommand.class,    // describe-cluster
    ListClustersCommand.class,       // list-clusters
    UpdateClusterCommand.class,      // update-cluster
    CreateAssociationCommand.class,  // create-association
    DeleteAssociationCommand.class,  // delete-association
    DescribeAssociationCommand.class,// describe-association
    ListAssociationsCommand.class,   // list-associations
    CreateTenantCommand.class,       // create-tenant
    DeleteTenantCommand.class,       // delete-tenant
    StopTenantCommand.class,         // stop-tenant
    ResumeTenantCommand.class,       // resume-tenant
    VersionCommand.class,            // version
    // Deprecated aliases (Phase 2):
    CreateCommand.class,             // create → routes to register-cluster / create-association / create-tenant
    DeleteCommand.class,             // delete → routes to deregister-cluster / delete-association / delete-tenant
})
public class EksDxCommand {}
```

## Documentation Deliverables

| Document | Location | Purpose |
|----------|----------|---------|
| CLI Reference | `docs/user-guides/cli/cli-reference.md` (planned) | Complete command reference with examples |
| Migration Guide | `docs/customer/cli/MIGRATION_v2.2.md` | Old → new command mapping for existing users |
| Man pages | Generated from picocli `--help` | Built into native binary |

## Open Questions — Resolved

### 1. `describe-tenant` vs `describe-cluster` — Keep separate

They answer different questions:
- `describe-cluster` → logical view: issuer, JWKS fingerprint, association count, registered-at
- `describe-tenant` → physical view: instance-id, state, public IP, IAM role, subnet, k8s version

External clusters (k3s, microk8s) have no tenant. Tenants always have both views. No unification needed.

### 2. `register-cluster` auto-discover — Default behavior

Auto-discover from kube-apiserver is the default (reads `~/.kube/config`). Explicit `--issuer` + `--jwks-file` flags override when the cluster isn't reachable from the CLI (VPN, CI/CD, bastion). No additional flag needed — if `--issuer` is omitted, it discovers; if provided, it uses the explicit value.

### 3. `list-clusters` — Unified with type column (Option B)

`list-clusters` shows ALL registered clusters (external + tenant-backed) with a `type` column:

```
$ eks-dx list-clusters
  NAME            TYPE       STATUS   ASSOCIATIONS   REGISTERED
  my-k3s          external   active   3              2026-06-20
  acme-staging    tenant     running  7              2026-06-24
  dev-cluster     tenant     stopped  1              2026-06-22
```

`list-tenants` is a convenience filter showing only tenant-type clusters with additional infra columns:

```
$ eks-dx list-tenants
  NAME            STATE    IP            INSTANCE        ARCH    K8S
  acme-staging    running  54.12.34.56   i-0abc123...    arm64   1.32
  dev-cluster     stopped  -             i-0def456...    x86_64  1.31
```

Same data source (DynamoDB), different projection. A tenant always appears in both lists.
