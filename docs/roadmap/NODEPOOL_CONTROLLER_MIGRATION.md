# Roadmap — NodePool Controller Migration

## Status: Planned

## Context

`configure-nodepools.sh` (from `eks-d-xpress/node-pools/`) is an imperative bash script that must be
run on the control-plane EC2 to bootstrap Karpenter node pools. It performs five distinct jobs:

| # | Job | Current implementation |
|---|-----|----------------------|
| 1 | Resolve K8s minor version | `kubectl version --output=json` |
| 2 | Resolve AMI ID | SSM `GetParameter` using variant + minor version path |
| 3 | Discover tenant AWS resources | `ec2:DescribeSubnets`, `ec2:DescribeSecurityGroups` by tag/name |
| 4 | Discover cluster bootstrap data | `endpoints/kubernetes`, `configmap/kube-root-ca.crt`, `configmap/kubeadm-config` |
| 5 | Patch `EC2NodeClass` ValidationSucceeded | `kubectl proxy` + raw `curl PATCH` to the status subresource |

Jobs 1–4 feed the Helm chart render. Job 5 is a workaround: Karpenter's built-in EKS validation
webhook (which sets `ValidationSucceeded=True`) is absent when `eksControlPlane=false`, so
Karpenter never considers the `EC2NodeClass` ready and refuses to provision nodes.

The userData injection (job 4 consumed values) is tracked separately in
`KARPENTER_EC2NODECLASS_WEBHOOK.md`. This document covers jobs 2, 3, and 5, plus the overall
lifecycle automation that replaces the script.

---

## Problem: ValidationSucceeded Hack (most urgent)

The current workaround in `configure-nodepools.sh` is fragile:

```bash
kubectl proxy --port=8099 &>/dev/null &
PROXY_PID=$!
sleep 1
curl -sf -X PATCH "http://localhost:8099/apis/karpenter.k8s.aws/v1/ec2nodeclasses/default/status" \
  -H "Content-Type: application/merge-patch+json" \
  -d "{\"status\":{\"conditions\":[{\"type\":\"ValidationSucceeded\",\"status\":\"True\",...}]}}" > /dev/null
kill $PROXY_PID 2>/dev/null
```

**Failure modes:**
- `kubectl proxy` takes >1s to start → `curl` fails silently, Karpenter never provisions
- Script is not idempotent on retry (proxy port 8099 already bound)
- Does not handle `EC2NodeClass` updates — status reverts if Karpenter re-evaluates and clears conditions
- Requires `kubectl` and `curl` on the control-plane EC2; not usable in GitOps flows

---

## Proposed Solution

### Component 1: `eks-dx-karpenter-support` (new module)

A single Quarkus application combining both the mutating webhook (userData injection, from
`KARPENTER_EC2NODECLASS_WEBHOOK.md`) and the controller reconciler (ValidationSucceeded + AMI
resolution + NodePool lifecycle). One deployment, one ServiceAccount, one set of RBAC rules.
This is the standard operator pattern — a single binary registers an HTTP webhook endpoint and
runs a reconciler loop concurrently.

**Module location:**
```
eks-dx-karpenter-support/
  src/main/java/.../
    resource/Ec2NodeClassWebhookResource.java   # POST /mutate-ec2nodeclass (userData injection)
    reconciler/Ec2NodeClassReconciler.java      # JOSDK reconciler (ValidationSucceeded + AMI)
    reconciler/TenantNodePoolConfigReconciler.java  # NodePool lifecycle (Phase 3)
    service/ClusterIdentityService.java         # resolves + caches cluster bootstrap fields
    service/UserDataMergeService.java           # TOML merge (BR) / MIME merge (AL2)
    service/ValidationConditionService.java     # patches status subresource
    service/AmiResolutionService.java           # SSM AMI lookup
  src/main/resources/application.properties
  Dockerfile
```

`eks-dx-pod-identity-webhook` remains unchanged as the reference implementation for standalone
Quarkus admission webhooks. `eks-dx-karpenter-support` supersedes the planned
`eks-dx-ec2nodeclass-webhook` module described in `KARPENTER_EC2NODECLASS_WEBHOOK.md`.

**Reconciler logic (per EC2NodeClass CREATE/UPDATE):**

1. Check if `spec.amiSelectorTerms` contains a concrete `id:` — if yes, AMI is already resolved, skip AMI step.
2. If `spec.amiSelectorTerms` contains a variant annotation (see Component 2), resolve AMI via SSM and patch `spec.amiSelectorTerms[0].id`.
3. Patch `status.conditions` to set `ValidationSucceeded=True` (replaces the kubectl proxy hack).

The patch to `status.conditions` is idempotent — if the condition already exists with `status=True`
and matches the current `observedGeneration`, the reconciler skips the write.

**MutatingWebhookConfiguration is NOT used for status patching** — status is a server-side concern,
not a mutation of user-submitted spec. The reconciler uses a standard JOSDK `Reconciler<EC2NodeClass>`.

**RBAC:**
```yaml
rules:
- apiGroups: ["karpenter.k8s.aws"]
  resources: ["ec2nodeclasses"]
  verbs: ["get", "list", "watch", "patch"]
- apiGroups: ["karpenter.k8s.aws"]
  resources: ["ec2nodeclasses/status"]
  verbs: ["patch"]
- apiGroups: [""]
  resources: ["configmaps"]
  resourceNames: ["kubeadm-config", "kube-root-ca.crt", "eks-dx-config"]
  verbs: ["get"]
```

---

### Component 2: AMI Auto-Resolution

The script selects an SSM path based on `NODE_VARIANT` and k8s minor version:

| NODE_VARIANT | SSM path |
|-------------|----------|
| `al2023` | `/aws/service/eks/optimized-ami/1.{minor}/amazon-linux-2023/{arch}/standard/recommended/image_id` |
| `al2023-gpu` | `/aws/service/eks/optimized-ami/1.{minor}/amazon-linux-2023/{arch}/nvidia/recommended/image_id` |
| `al2023-neuron` | `/aws/service/eks/optimized-ami/1.{minor}/amazon-linux-2023/{arch}/neuron/recommended/image_id` |
| `bottlerocket` | `/aws/service/bottlerocket/aws-k8s-{minor}/{arch}/latest/image_id` |
| `bottlerocket-gpu` | `/aws/service/bottlerocket/aws-k8s-{minor}-nvidia/{arch}/latest/image_id` |
| `bottlerocket-neuron` | `/aws/service/bottlerocket/aws-k8s-{minor}-neuron/{arch}/latest/image_id` |

**Proposed annotation-driven resolution:**

Users annotate `EC2NodeClass` with the desired variant; the controller resolves and injects the AMI ID:

```yaml
apiVersion: karpenter.k8s.aws/v1
kind: EC2NodeClass
metadata:
  name: default
  annotations:
    eks-dx.codriverlabs.ai/node-variant: "al2023"   # controller resolves → patches amiSelectorTerms
spec:
  amiFamily: Custom
  amiSelectorTerms: []   # controller fills this in
  ...
```

K8s minor version is read from `kubectl version` equivalent: the server's `ServerVersion.Minor` via
the in-cluster API client (same source as the script).

AMI IDs are cached in-memory per `(variant, arch, minor)` with a 24-hour TTL. On TTL expiry,
the controller re-resolves and patches any `EC2NodeClass` using the stale AMI ID (enables rolling
AMI updates without manual re-runs of the script).

**IAM permissions required on the tenant EC2 instance role** (already exists):
```
ssm:GetParameter on /aws/service/eks/optimized-ami/*
ssm:GetParameter on /aws/service/bottlerocket/*
```

---

### Component 3: NodePool Lifecycle Automation

The script's resource discovery (subnet, SG) and NodePool creation can be driven by a new CRD:

```yaml
apiVersion: eks-dx.codriverlabs.ai/v1alpha1
kind: TenantNodePoolConfig
metadata:
  name: default
  namespace: kube-system
spec:
  tenantId: "<from cluster.env>"
  awsRegion: "us-east-1"
  arch: "arm64"
  nodeVariant: "al2023"
  capacityType: "spot"
  instanceCategories: ["m", "c", "r"]
  instanceGenerationGt: 5
  cpuLimit: "100"
  memoryLimit: "100Gi"
```

The controller reconciles `TenantNodePoolConfig` → creates/updates `EC2NodeClass` + `NodePool`.

Resource discovery during reconciliation:

| Resource | Discovery method |
|----------|-----------------|
| `subnetId` | `ec2:DescribeSubnets` filtered by `tag:Developer={tenantId}` + `tag:SubnetType=Public` |
| `securityGroupId` | `ec2:DescribeSecurityGroups` filtered by `group-name={tenantId}-eks-d-xpress` |
| `instanceProfile` | Derived: `eks-d-xpress-tenant-{tenantId}-instance-role` (naming convention, no API call) |

The controller writes discovered IDs into `status.resolvedSubnetId`, `status.resolvedSecurityGroupId`
for observability, and re-reconciles on a 1-hour period to pick up infra changes.

**AWS credential access for resource discovery:** The controller runs on the control-plane node and
uses the instance profile (IRSA-equivalent via Pod Identity agent after it's installed). The tenant
IAM role already has `ec2:DescribeSubnets` and `ec2:DescribeSecurityGroups` in its policy.

---

## Migration Path

### Phase 1 — ValidationSucceeded controller (unblocks Karpenter, replaces the hack)

Deploy `eks-dx-ec2nodeclass-controller` with only the status patching logic. The Helm chart
continues to be applied manually via `configure-nodepools.sh`. The controller replaces the
`kubectl proxy` + `curl` block entirely.

**configure-nodepools.sh change:** remove the `kubectl proxy` block (lines ~83–97). The controller
handles it asynchronously within seconds of `EC2NodeClass` creation.

### Phase 2 — AMI annotation resolution

Add `AmiResolutionService` to the controller. Users annotate `EC2NodeClass` with
`eks-dx.codriverlabs.ai/node-variant` instead of supplying `amiSelectorTerms`. Controller patches
the spec. Helm chart drops `amiId` value; `configure-nodepools.sh` drops the SSM lookup block.

### Phase 3 — TenantNodePoolConfig CRD

Deploy the `TenantNodePoolConfig` CRD and controller reconciler. The CLI (`eks-dx nodepool apply`)
creates the CRD instance; controller owns `EC2NodeClass` + `NodePool` lifecycle.
`configure-nodepools.sh` is retired — replaced by:

```bash
eks-dx nodepool apply --tenant-id "$TENANT_ID" --variant al2023 --capacity-type spot
```

The CLI command creates a `TenantNodePoolConfig` in the cluster; the controller takes it from there.

### Phase 4 — AMI rolling updates

Enable the 24-hour AMI TTL cache + re-patch logic. When a newer EKS-optimized AMI is published to
SSM, the controller detects the stale ID on next reconcile and updates `EC2NodeClass.spec.amiSelectorTerms`.
Karpenter's node drift detection then replaces existing nodes on its schedule.

---

## Relationship to KARPENTER_EC2NODECLASS_WEBHOOK.md

`KARPENTER_EC2NODECLASS_WEBHOOK.md` described a standalone `eks-dx-ec2nodeclass-webhook` module.
That plan is superseded — all concerns are consolidated into `eks-dx-karpenter-support`:

| Concern | Handler |
|---------|---------|
| `EC2NodeClass.spec.userData` injection (cluster bootstrap data) | webhook endpoint (`/mutate-ec2nodeclass`) |
| `EC2NodeClass.status.conditions[ValidationSucceeded]` | JOSDK reconciler |
| `EC2NodeClass.spec.amiSelectorTerms` resolution | JOSDK reconciler |
| `EC2NodeClass` + `NodePool` lifecycle from CRD | JOSDK reconciler (`TenantNodePoolConfigReconciler`) |

Both the webhook and the reconciler touch the same `EC2NodeClass` resource but different fields —
no conflict. The webhook mutates spec at admission time; the reconciler patches status and AMI
selector post-admission.

---

## Risk Assessment

| Risk | Mitigation |
|------|------------|
| Controller down, `ValidationSucceeded` never set | Karpenter holds but does not error; controller catches up on restart |
| AMI ID rotated mid-node-lifecycle | Karpenter's drift detection handles replacement; controller only updates `amiSelectorTerms`, not running instances |
| Subnet/SG discovery returns stale data after infra change | Controller re-reconciles hourly; force reconcile via `kubectl annotate` |
| `TenantNodePoolConfig` CRD conflict with manual `EC2NodeClass` | Ownership labels prevent controller from overwriting manually managed resources (controller skips unlabelled EC2NodeClasses) |

---

## Implementation Order

1. **Phase 1** — `eks-dx-karpenter-support` module: `Ec2NodeClassReconciler` with `ValidationConditionService` + webhook endpoint for userData injection (consolidates `KARPENTER_EC2NODECLASS_WEBHOOK.md` Phase 1)
2. **Phase 2** — `AmiResolutionService` + annotation-driven SSM lookup
3. **Phase 3** — `TenantNodePoolConfig` CRD + CLI command; retire `configure-nodepools.sh`
4. **Phase 4** — AMI TTL cache + rolling update support
