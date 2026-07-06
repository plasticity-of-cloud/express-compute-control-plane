# Cross-Cloud EKS Pod Identity Feasibility Analysis

**Status**: RFC / Feasibility Study  
**Date**: 2026-07-06  
**Author**: Platform Engineering  
**Scope**: Running EKS-DX Pod Identity on Azure AKS, GCP GKE, on-prem Kubernetes, and third-party clouds

---

## Executive Summary

EKS-DX Pod Identity can extend beyond AWS-hosted Kubernetes to **any Kubernetes cluster on any cloud or on-premises infrastructure**. The architecture is inherently cloud-agnostic on the client side — the only external dependency is an HTTPS path from the in-cluster auth-proxy to the EKS-DX API Gateway in AWS.

This enables a powerful use case: **Kubernetes operators that manage AWS resources (Lambda MicroVMs, S3, DynamoDB, etc.) can run on Azure, GCP, or on-prem while receiving native IAM credentials through the standard AWS SDK credential chain** — identical behavior to pods running on real EKS.

---

## Motivating Use Case: AWS Lambda MicroVM Operators on Azure/GCP

```
Azure AKS / GCP GKE cluster
  ├── EKS Pod Identity Agent (DaemonSet, hybrid mode)
  ├── eks-dx-pod-identity-webhook (admission controller)
  ├── eks-dx-auth-proxy (credential backend)
  └── lambda-microvm-operator pods
        │
        ↓ AWS SDK resolves credentials via standard chain
        ↓ → Pod Identity Agent (169.254.170.23, intercepts)
        ↓ → auth-proxy (TokenReview + forward)
        ↓ → EKS-DX credential-service Lambda (JWKS + STS)
        ↓ ← temporary IAM credentials
        ↓
        → AWS Lambda CreateFunction / InvokeFunction APIs
        → Lambda MicroVMs serve Internet_Ingress traffic in AWS
```

The operator control plane lives wherever it's most convenient (co-located with other workloads, closest to operators, cheapest). AWS resources are provisioned and managed with proper IAM identity — no long-lived credentials, no key rotation, no instance profiles.

---

## Architecture: Credential Exchange Flow

```
Pod → EKS Pod Identity Agent → eks-dx-auth-proxy (in-cluster)
  │
  ├─ 1. TokenReview (fast-fail — K8s API validates JWT signature + audience)
  │
  └─ 2. Forward to credential-service Lambda (via API Gateway, HTTPS)
       │
       ├─ 3. JWKS validation (jose4j, DynamoDB-cached JWKS)
       ├─ 4. Association lookup (DynamoDB: CLUSTER#name / namespace#sa → roleArn)
       ├─ 5. STS AssumeRole (with session tags from token claims)
       └─ 6. Return temporary AWS credentials
```

### Component Roles

| Component | Runs Where | Role |
|-----------|-----------|------|
| EKS Pod Identity Agent | On every node (DaemonSet) | Intercepts 169.254.170.23, serves `/v1/credentials` to pods |
| eks-dx-pod-identity-webhook | In-cluster (admission) | Injects `AWS_CONTAINER_CREDENTIALS_FULL_URI` + projected SA token |
| eks-dx-auth-proxy | In-cluster (Service) | Agent's backend: TokenReview + forwards to Lambda |
| credential-service Lambda | AWS | JWKS validation, association lookup, STS AssumeRole |
| DynamoDB | AWS | Cluster registrations, pod identity associations, JWKS cache |

---

## Critical Dependency: EKS Pod Identity Agent and IMDSv2

### The Problem

The official EKS Pod Identity Agent (`github.com/aws/eks-pod-identity-agent`) has an **AWS credentials dependency at startup**:

```go
// cmd/server.go
cfg, err := config.LoadDefaultConfig(ctx)  // ← needs AWS credentials
```

The agent is an **AWS SDK client** that calls `eksauth.AssumeRoleForPodIdentity` with SigV4-signed requests:

```go
// internal/cloud/eksauth/service.go
creds, err := s.eksAuthService.AssumeRoleForPodIdentity(ctx, &eksauth.AssumeRoleForPodIdentityInput{
    ClusterName: aws.String(request.ClusterName),
    Token:       aws.String(request.ServiceAccountToken),
})
```

On EC2 nodes, credentials are provided by IMDSv2 (instance role). **On non-AWS nodes, there is no IMDS.**

### The Solution: Hybrid Mode

The agent already supports non-IMDS credential loading via `--rotate-credentials`:

```go
// cmd/server.go
if rotateCredentials {
    cfg.Credentials = aws.NewCredentialsCache(
        sharedcredsrotater.NewRotatingSharedCredentialsProvider(),
    )
}
```

The Helm chart has a dedicated **hybrid DaemonSet** variant:

```yaml
# charts/eks-pod-identity-agent/values.yaml
daemonsets:
  hybrid:
    create: false                          # ← enable for cross-cloud
    additionalArgs:
      "--rotate-credentials": "true"
    volumes:
    - name: aws-credentials
      hostPath:
        path: /eks-hybrid/.aws
        type: Directory
    volumeMounts:
    - mountPath: /root/.aws
      name: aws-credentials
      readOnly: true
    affinity:
      nodeAffinity:
        requiredDuringSchedulingIgnoredDuringExecution:
          nodeSelectorTerms:
            - matchExpressions:
                - key: "eks.amazonaws.com/compute-type"
                  operator: In
                  values:
                    - hybrid
```

Additionally, the `--endpoint` flag overrides where the `eksauth` SDK client sends requests:

```go
// cmd/server.go
serverCmd.Flags().StringVar(&overrideEksAuthEndpoint, "endpoint", "",
    "Override for EKS auth endpoint")
```

### Cross-Cloud Agent Configuration

For non-AWS nodes, the agent runs in hybrid mode with endpoint override:

```
eks-pod-identity-agent server \
  --cluster-name my-azure-cluster \
  --rotate-credentials \
  --endpoint http://eks-dx-auth-proxy.kube-system.svc.cluster.local:8080
```

### SigV4 Signing Question

The agent uses the AWS SDK's `eksauth.Client`, which SigV4-signs all requests. The eks-dx-auth-proxy (`EksAuthAgentResource.java`) does **not** validate SigV4 signatures — it receives the request as plain JAX-RS and reads the `token` field from the JSON body:

```java
// EksAuthAgentResource.java
public static class AgentRequest {
    @JsonProperty("token") public String token;
}

@POST
@Path("/{clusterName}/assets")
public Response assumeRoleForPodIdentityAssets(
        @PathParam("clusterName") String clusterName,
        AgentRequest request) { ... }
```

**Implication**: The credentials file on non-AWS nodes satisfies the SDK's signing requirement, but the signature is never validated by the auth-proxy. The credentials can be minimal/unprivileged — they just need to exist for the SDK to construct the request.

### Open Question

Should the auth-proxy validate the SigV4 signature from the agent for defense-in-depth? This would require:
- Real (minimal) IAM credentials on each non-AWS node
- Auth-proxy to verify the signature (adds complexity)
- Benefit: prevents unauthorized processes from calling the auth-proxy directly

Current position: TokenReview already validates the pod's SA token, providing authentication. The agent→auth-proxy path is in-cluster only (ClusterIP service). Network policies can restrict access further.

---

## What Already Works Today

### Helm Charts (Published to ghcr.io)

All in-cluster components have Helm charts generated via `quarkus-helm` at build time:

| Chart | Registry | Description |
|-------|----------|-------------|
| `eks-d-xpress-auth-proxy` | ghcr.io/codriverlabs | TokenReview + Lambda forwarding |
| `eks-d-xpress-pod-identity-webhook` | ghcr.io/codriverlabs | Admission webhook (env + volume injection) |
| `eks-d-xpress-karpenter-support` | ghcr.io/plasticity-of-cloud | EC2NodeClass webhook + reconciler |

These are installable on **any Kubernetes cluster** via standard `helm install`.

### Webhook Behavior (Cloud-Agnostic)

The webhook (`PodIdentityMutator.java`) injects:
- `AWS_CONTAINER_CREDENTIALS_FULL_URI = http://169.254.170.23/v1/credentials`
- `AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE = /var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token`
- Projected SA token volume with `audience: pods.eks.amazonaws.com`

This is pure Kubernetes admission mutation — no cloud provider dependency.

### Agent Network Setup (Cloud-Agnostic)

The agent's `initialize` command creates a dummy network interface:
- Interface name: `pod-id-link0`
- IPv4: `169.254.170.23/32` (link-local, not IMDS range)
- IPv6: `fd00:ec2::23/128`
- Route table entries directing traffic to the interface

This uses `netlink` (Linux kernel API) — works on any Linux node regardless of cloud provider. The address `169.254.170.23` is distinct from IMDS (`169.254.169.254`) and is not intercepted by any cloud provider's metadata service.

### JWKS Registration (One-Time CLI)

```bash
eks-dx register-cluster my-azure-cluster \
  --jwks-uri https://oidc.prod-aks.azure.com/<tenant>/<cluster>/discovery/keys
```

For AKS/GKE: OIDC issuer is publicly reachable, CLI can fetch JWKS automatically.  
For on-prem: Manual JWKS export and registration via `--jwks` inline parameter.

---

## Cross-Cloud Deployment Model

### Per-Node Requirements

| Requirement | AWS (EC2) | Azure (AKS) | GCP (GKE) | On-Prem |
|---|---|---|---|---|
| Agent credentials | IMDSv2 (automatic) | File at `/eks-hybrid/.aws` | File at `/eks-hybrid/.aws` | File at `/eks-hybrid/.aws` |
| Network: 169.254.170.23 | Agent init (automatic) | Agent init (automatic) | Agent init (automatic) | Agent init (automatic) |
| Egress to API Gateway | Direct / VPC | Public internet or ExpressRoute | Public internet or Interconnect | Public internet or Direct Connect |
| K8s API (TokenReview) | In-cluster | In-cluster | In-cluster | In-cluster |

### Credentials File Provisioning on Non-AWS Nodes

Options for providing the `/eks-hybrid/.aws` credentials file:

1. **DaemonSet init container**: Fetch credentials from a secrets manager (Azure Key Vault, GCP Secret Manager, HashiCorp Vault) and write to hostPath
2. **Cloud-init / node bootstrap**: Bake into the node image or deliver via user-data
3. **Kubernetes Secret → hostPath**: Mount a Secret as a volume in the agent DaemonSet (requires modification to hybrid chart)
4. **Minimal static credentials**: Since auth-proxy doesn't validate SigV4, a static IAM user with no permissions would satisfy the SDK

### Helm Values for Cross-Cloud Deployment

```yaml
# EKS Pod Identity Agent — cross-cloud variant
daemonsets:
  cloud:
    create: false    # disable standard (IMDS) variant
  hybrid:
    create: true     # enable file-based credentials variant
    additionalArgs:
      "--rotate-credentials": "true"
      "--endpoint": "http://eks-dx-auth-proxy.kube-system.svc.cluster.local:8080"
      "--cluster-name": "my-azure-cluster"

# eks-dx-auth-proxy
env:
  EKS_DX_ENDPOINT: "https://eks-dx.codriverlabs.ai"  # API Gateway URL

# eks-dx-pod-identity-webhook
# (no cloud-specific config needed)
```

---

## Network Connectivity Options

### Without AWS Interconnect (Public Internet)

```
Non-AWS Node
  └── eks-dx-auth-proxy
        └── HTTPS (443) → API Gateway (eks-dx.codriverlabs.ai)
                              └── credential-service Lambda
                                    └── STS AssumeRole → credentials
```

| Aspect | Characteristics |
|---|---|
| Latency | 50–150ms per credential exchange (cached by agent, 3h TTL) |
| Bandwidth | Negligible (~2KB per request/response) |
| Security | TLS 1.3, API Gateway endpoint |
| Availability | API Gateway multi-AZ, public DNS |
| Egress cost | < $0.01/month for credential traffic |

### With AWS Direct Connect / Azure ExpressRoute / GCP Interconnect

```
Non-AWS Node
  └── eks-dx-auth-proxy
        └── Private VIF / ExpressRoute → Transit Gateway → VPC Endpoint
                                                              └── API Gateway (private)
                                                                    └── credential-service Lambda
```

| Aspect | Without Interconnect | With Interconnect |
|---|---|---|
| Credential exchange latency | 50–150ms | 5–20ms |
| Traffic path | Public internet | Private backbone |
| Compliance (FedRAMP, PCI, SOC2) | Harder to certify | Meets private connectivity requirements |
| Availability | Internet-dependent | Dedicated path, SLA-backed |
| Cost | Egress only | Port hours + data transfer |

### AWS PrivateLink Option

Expose the API Gateway as a VPC endpoint service. Non-AWS networks connected via Direct Connect/ExpressRoute reach it through VPC peering — credential traffic never touches the public internet.

---

## Positioning Shift

| Current | Cross-Cloud |
|---------|-------------|
| "EKS Pod Identity for non-EKS clusters **on AWS**" | "EKS Pod Identity for **any Kubernetes cluster, anywhere**" |

### Target Use Cases

1. **Multi-cloud operators**: K8s controllers on Azure/GCP managing AWS resources (Lambda MicroVMs, S3, ECS, Bedrock)
2. **Hybrid cloud**: On-prem workloads accessing AWS services with pod-level IAM granularity
3. **CI/CD on non-AWS K8s**: Build/test pipelines on GKE/AKS needing AWS credentials
4. **Edge/IoT**: K3s clusters at edge locations accessing AWS backend services
5. **Sovereignty/compliance**: Control plane in regulated jurisdiction, data plane in AWS

---

## Implementation Gaps

### Must Have (P0)

| Gap | Effort | Description |
|-----|--------|-------------|
| Cross-cloud Helm values profile | S | Helm values overlay for hybrid agent + endpoint override |
| Credentials file provisioning guide | S | Document options for non-AWS nodes |
| Network egress policy examples | S | NetworkPolicy allowing auth-proxy → API Gateway |
| `eks-dx register-cluster` for AKS/GKE | M | Auto-fetch OIDC issuer/JWKS from managed K8s providers |
| Integration test on AKS | M | Validate end-to-end flow on non-AWS K8s |

### Should Have (P1)

| Gap | Effort | Description |
|-----|--------|-------------|
| Dummy credentials DaemonSet | S | Init container providing minimal creds for SDK signing |
| Auth-proxy SigV4 validation (optional) | M | Defense-in-depth: validate agent's request signature |
| Cross-cloud install documentation | M | User guide for Azure/GCP/on-prem deployment |
| Helm chart `NOTES.txt` for multi-cloud | S | Post-install instructions for non-AWS |
| Latency/availability monitoring | S | CloudWatch metrics for cross-cloud credential exchanges |

### Nice to Have (P2)

| Gap | Effort | Description |
|-----|--------|-------------|
| PrivateLink endpoint exposure | L | Private API Gateway endpoint for interconnected networks |
| CLI `eks-dx install` for cross-cloud | M | Automated Helm install with cloud-specific defaults |
| Agent credential rotation via STS | M | Replace static file with STS-rotated session credentials |
| Multi-region API Gateway failover | L | Regional endpoints for latency-sensitive cross-cloud paths |

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Agent SDK version drift (AWS changes eksauth API) | Low | High | Pin agent version, test on upgrade |
| Azure/GCP blocks link-local 169.254.170.x | Very Low | High | Address is outside IMDS range (169.254.169.254), not intercepted |
| Credential file exposure on non-AWS nodes | Medium | Medium | Use secrets manager, restrict hostPath permissions, short-lived creds |
| API Gateway endpoint unreachable (internet outage) | Low | Medium | Agent caches credentials for 3h; Interconnect for HA |
| SigV4 bypass allows unauthorized auth-proxy access | Low | Low | TokenReview validates pod identity; NetworkPolicy restricts access |

---

## Conclusion

Cross-cloud EKS Pod Identity is **architecturally viable today** with the existing EKS-DX components. The official EKS Pod Identity Agent's hybrid mode (file-based credentials + endpoint override) eliminates the IMDS dependency. The auth-proxy, webhook, and credential-service are cloud-agnostic by design.

The primary work items are:
1. Helm values profile for cross-cloud deployment
2. Credentials file provisioning strategy for non-AWS nodes
3. Integration testing on AKS/GKE
4. Documentation

No architectural changes to EKS-DX are required. The credential exchange protocol, DynamoDB data model, JWKS validation, and STS AssumeRole flow all work unchanged.
