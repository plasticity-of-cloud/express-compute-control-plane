# Deployment Modes

## Summary

The Express Compute control plane supports three deployment modes that control which Lambda
functions are deployed and whether the `express-compute-infra` stack is required as a
dependency.

## Motivation

Currently the CDK stack unconditionally resolves SSM parameters from the
`express-compute-infra` stack (launch templates, VPC ID) and deploys the tenant-service
Lambda. This creates a hard dependency on the infra stack even when deploying only for
self-managed cluster registration — a use case that needs nothing beyond the credential
and management Lambdas.

Introducing explicit deployment modes:
1. Removes the infra stack dependency for self-managed-only deployments.
2. Makes the deployment scope explicit and auditable.
3. Allows lighter deployments (fewer Lambdas, fewer IAM permissions) for users who
   only need Workload Identity credential exchange.

## Deployment Modes

| Mode | CDK Context Value | Requires Infra Stack | Components Deployed |
|------|-------------------|---------------------|---------------------|
| **self-managed** | `deploymentMode=self-managed` | No | credential-service, mgmt-service, API Gateway, DynamoDB |
| **managed** | `deploymentMode=managed` | Yes | All of self-managed + tenant-service (Function URL, KMS, SSM lookups) |
| **hybrid** | `deploymentMode=hybrid` (default) | Yes | Same as managed (supports both flows) |

### self-managed

For operators who bring their own Kubernetes clusters (k3s, microk8s, EKS-D, kubeadm)
and register them with their own JWKS/issuer. No EC2 provisioning capability.

**What is deployed:**
- credential-service Lambda (SnapStart) — credential exchange hot path
- mgmt-service Lambda — cluster/association CRUD, JWKS refresh
- API Gateway (REST) — routes for `/clusters/{name}/assets`, management endpoints
- DynamoDB tables — `ecp-clusters`, `ecp-workload-identities`
- CloudWatch log groups
- IAM roles for credential-service and mgmt-service only

**What is NOT deployed:**
- tenant-service Lambda
- Tenant Function URL
- KMS CA signing key
- DynamoDB `express-compute-tenants` table
- SSM parameter lookups from infra stack
- Tenant-scoped IAM permissions (EC2, VPC, SQS, DLM, Secrets Manager)

**SSM parameters written:**
- `/express-compute/control-plane/api/endpoint` — API Gateway URL (always written)

### managed

For operators who want Express Compute to provision and manage EKS-D clusters on EC2.
Only managed clusters are supported — self-managed registration is disabled.

**Requires:** `express-compute-infra` stack deployed first (provides launch templates,
AMI IDs, VPC ID via SSM).

**What is deployed:**
- Everything in self-managed mode, plus:
- tenant-service Lambda with Function URL (streaming)
- KMS asymmetric key for CA signing
- DynamoDB `express-compute-tenants` table
- SSM parameter lookups (`/express-compute/infra/...`)
- Full tenant IAM permissions

**Behavior difference from hybrid:** The `POST /clusters` endpoint rejects requests
with `jwks`/`issuer` fields (self-managed mode is disabled at the API level).

### hybrid (default)

Supports both self-managed cluster registration AND managed tenant provisioning.
This is the current behavior and the default if no `deploymentMode` is specified.

**Requires:** `express-compute-infra` stack deployed first.

**What is deployed:** Same as managed.

**Behavior:** `POST /clusters` accepts both forms — presence of `jwks` field triggers
self-managed registration; absence triggers managed provisioning.

## CDK Implementation

### Context Flag

```bash
# Self-managed only (no infra stack needed)
cdk deploy --context deploymentMode=self-managed

# Managed only
cdk deploy --context deploymentMode=managed

# Hybrid (default — backward compatible)
cdk deploy
cdk deploy --context deploymentMode=hybrid
```

### Changes to `ExpressComputeControlPlaneStack.java`

1. Read `deploymentMode` from CDK context (default: `hybrid`).
2. Define a boolean `deployTenantService = !mode.equals("self-managed")`.
3. Wrap in conditionals:
   - SSM parameter lookups (lines 137-146)
   - Tenants DynamoDB table
   - KMS CA signing key
   - tenant-service Lambda + Function URL
   - Tenant IAM policy statements
   - SSM parameter for tenant Function URL
4. credential-service and mgmt-service are always deployed.

### Environment Variable Propagation

When `deployTenantService = false`:
- `ECP_LT_*` and `ECP_VPC_ID` are not set on any Lambda
- `ECP_TENANTS_TABLE` is not set
- `ECP_KMS_CA_KEY_ID` is not set

### deploy-local.sh Integration

```bash
# Self-managed deploy
./deploy-local.sh --context deploymentMode=self-managed

# Managed deploy
./deploy-local.sh --context deploymentMode=managed

# Hybrid deploy (default, backward compatible)
./deploy-local.sh
```

## Tenant-Service API Guard (managed mode)

When deployed in `managed` mode (not hybrid), the tenant-service should reject
self-managed registration attempts. This is enforced via an environment variable:

```
ECP_DEPLOYMENT_MODE=managed
```

The `ClusterResource.createCluster()` method checks:
```java
if (selfManaged && "managed".equals(deploymentMode)) {
    return error(400, "InvalidParameterException",
        "Self-managed cluster registration is disabled in managed-only mode");
}
```

In hybrid mode (or when the env var is absent), both flows are accepted as today.

## Migration Path

- Existing deployments continue to work unchanged (hybrid is the default).
- New self-managed-only deployments can skip the infra stack entirely.
- Switching from self-managed to hybrid/managed requires deploying the infra stack
  first, then redeploying with the new mode.

## Testing

- CDK synth with each mode (unit test: template snapshot or assertion)
- Verify self-managed synth does NOT produce tenant-service resources
- Verify managed/hybrid synth DOES produce tenant-service resources
- Verify managed mode rejects self-managed requests at API level
- Existing integration tests continue passing (hybrid mode is default)
