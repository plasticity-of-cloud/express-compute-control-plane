# Roadmap — Karpenter EC2NodeClass Webhook Injection

## Status: Implemented (v2.0.0, June 2026)

**Module:** `ecp-karpenter-support`
**Key files:** `Ec2NodeClassWebhookResource.java`, `UserDataMergeService.java`

## Problem

Cluster-specific fields required by Karpenter's `EC2NodeClass` (API server endpoint, CA bundle, service CIDR, cluster DNS IP) are currently injected via Helm chart values:

```yaml
# BottleRocket variant
userData: |
  [settings.kubernetes]
  api-server = {{ .Values.nodeConfig.apiServerEndpoint | quote }}
  cluster-certificate = {{ .Values.nodeConfig.certificateAuthority | quote }}
  cluster-name = {{ .Values.clusterName | quote }}
  cluster-dns-ip = {{ .Values.nodeConfig.clusterDnsIp | quote }}

# AL2/AL2023 variant (MIME multipart / NodeConfig)
userData: |
  MIME-Version: 1.0
  Content-Type: multipart/mixed; boundary="//"
  --//
  Content-Type: application/node.eks.aws
  ---
  apiVersion: node.eks.aws/v1alpha1
  kind: NodeConfig
  spec:
    cluster:
      name: {{ .Values.clusterName }}
      apiServerEndpoint: {{ .Values.nodeConfig.apiServerEndpoint }}
      certificateAuthority: {{ .Values.nodeConfig.certificateAuthority }}
      cidr: {{ .Values.nodeConfig.serviceCidr }}
    kubelet:
      flags:
        - "--node-labels=karpenter.sh/nodepool=default"
  --//--
```

These values are discovered at install time via:

```bash
API_SERVER="https://$(kubectl get endpoints kubernetes -o jsonpath='{.subsets[0].addresses[0].ip}'):6443"
CA_BUNDLE=$(sudo cat /etc/kubernetes/pki/ca.crt | base64 -w0 || \
            kubectl get configmap kube-root-ca.crt -n kube-system -o jsonpath='{.data.ca\.crt}' | base64 -w0)
SERVICE_CIDR=$(kubectl get configmap kubeadm-config -n kube-system \
  -o jsonpath='{.data.ClusterConfiguration}' | grep serviceSubnet | awk '{print $2}')
CLUSTER_DNS_IP=$(python3 -c "
import ipaddress
net = ipaddress.ip_network('${SERVICE_CIDR}', strict=False)
print(str(list(net.hosts())[9]))
")
```

**Pain points:**
- Values must be re-supplied on every Helm upgrade
- Drift risk if cluster endpoint or CA rotates
- Couples Karpenter chart config to ecp cluster registration data

## Proposed Solution

A new, standalone module `ecp-ec2nodeclass-webhook` (separate from `ecp-workload-identity-webhook`, which serves only as a reference implementation). On `CREATE` and `UPDATE` of `EC2NodeClass`, the webhook:

1. Reads cluster identity from in-cluster sources (same discovery logic above, but server-side)
2. Detects the node variant from `EC2NodeClass.spec.amiFamily` (`Bottlerocket` vs `AL2`/`AL2023`)
3. Merges the required fields into `spec.userData`, preserving any existing user-supplied content

### New Module

```
ecp-ec2nodeclass-webhook/   # Standalone Quarkus app, mirrors structure of ecp-workload-identity-webhook
  src/main/java/.../
    resource/Ec2NodeClassWebhookResource.java   # POST /mutate-ec2nodeclass
    service/ClusterIdentityService.java          # Resolves + caches cluster fields
    service/UserDataMergeService.java            # TOML merge (BR) / MIME merge (AL2)
  src/main/resources/application.properties
  Dockerfile
```

`ecp-workload-identity-webhook` is **not modified** — it remains the reference template for how to build a Quarkus admission webhook in this repo.

### Webhook Trigger

```yaml
apiVersion: admissionregistration.k8s.io/v1
kind: MutatingWebhookConfiguration
metadata:
  name: ecp-ec2nodeclass-injector
webhooks:
- name: ec2nodeclass.ecp.codriverlabs.ai
  admissionReviewVersions: ["v1"]
  clientConfig:
    service:
      name: ecp-ec2nodeclass-webhook
      namespace: kube-system
      path: /mutate-ec2nodeclass
  rules:
  - apiGroups: ["karpenter.k8s.aws"]
    apiVersions: ["v1"]
    resources: ["ec2nodeclasses"]
    operations: ["CREATE", "UPDATE"]
  sideEffects: None
  failurePolicy: Ignore   # Karpenter must not be blocked if webhook is down
```

`failurePolicy: Ignore` is intentional — a webhook outage must not prevent Karpenter from functioning.

### Cluster Identity Resolution (server-side)

The webhook resolves cluster fields once on startup and caches them (refreshed on SIGHUP or configurable TTL):

| Field | Source |
|-------|--------|
| `apiServerEndpoint` | `endpoints/kubernetes` in `default` namespace |
| `certificateAuthority` | `configmap/kube-root-ca.crt` in `kube-system` |
| `serviceCidr` | `configmap/kubeadm-config` in `kube-system` → `ClusterConfiguration.serviceSubnet` |
| `clusterDnsIp` | 10th host of `serviceCidr` (e.g. `10.96.0.0/12` → `10.96.0.10`) |
| `clusterName` | `configmap/ecp-config` in `kube-system` (written by `ecp` CLI at registration) |

### Mutation Logic

**BottleRocket** — inject into TOML `[settings.kubernetes]` block, merging with existing content:

```toml
[settings.kubernetes]
api-server = "<resolved>"
cluster-certificate = "<resolved>"
cluster-name = "<resolved>"
cluster-dns-ip = "<resolved>"
```

**AL2 / AL2023** — inject as the first MIME part (`application/node.eks.aws`), merging `spec.cluster.*` fields. Existing parts (e.g. custom bootstrap scripts) are preserved.

If `spec.userData` already contains the managed fields (idempotency check), the webhook is a no-op.

## Migration Path

### Phase 1 — Webhook ships managed fields (non-breaking)

- Deploy updated webhook with `/mutate-ec2nodeclass` handler
- Helm chart continues to supply values — webhook overwrites with authoritative values
- Validates that webhook-resolved values match chart-supplied values (logs warning on mismatch)

### Phase 2 — Remove values from Helm chart

- Drop `nodeConfig.*` and `clusterName` from `EC2NodeClass` section of Helm chart
- Webhook becomes the sole source of truth
- Helm chart retains only Karpenter-specific config (instance types, capacity type, AMI selector)

### Phase 3 — Auto-rotation support

- Webhook watches `kube-root-ca.crt` ConfigMap for changes
- On CA rotation: re-patches all existing `EC2NodeClass` resources to propagate new CA bundle
- Eliminates manual Helm upgrade on CA rotation

## RBAC Requirements

Additional permissions needed by the **`ecp-ec2nodeclass-webhook`** ServiceAccount:

```yaml
rules:
- apiGroups: [""]
  resources: ["endpoints"]
  resourceNames: ["kubernetes"]
  verbs: ["get"]
- apiGroups: [""]
  resources: ["configmaps"]
  resourceNames: ["kube-root-ca.crt", "kubeadm-config", "ecp-config"]
  verbs: ["get"]
- apiGroups: ["karpenter.k8s.aws"]
  resources: ["ec2nodeclasses"]
  verbs: ["get", "list", "patch"]   # patch needed for Phase 3 auto-rotation
```

## Risk Assessment

| Risk | Mitigation |
|------|------------|
| Webhook down blocks EC2NodeClass creation | `failurePolicy: Ignore` — Karpenter proceeds without injection |
| Stale cached values after CA rotation | Phase 3 watch + re-patch; Phase 1/2 TTL-based refresh |
| TOML/MIME merge corrupts existing userData | Unit-tested merge logic; webhook rejects on parse error rather than corrupting |
| `kubeadm-config` absent (non-kubeadm clusters) | Fallback: read `serviceCidr` from `ecp-config` ConfigMap written at registration |

## Implementation Order

1. **Phase 1** — new `ecp-ec2nodeclass-webhook` module (use `ecp-workload-identity-webhook` as structural reference), cluster identity resolver, TOML/MIME merge logic
2. **Phase 2** — Helm chart cleanup (coordinate with tenant Helm chart release)
3. **Phase 3** — CA rotation watch (independent, can ship later)
