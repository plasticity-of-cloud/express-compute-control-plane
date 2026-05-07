# EKS-DX Architecture (Revised)

## Overview

Two-tier architecture: a lightweight in-cluster proxy handles Kubernetes token validation, then forwards to a centralized Lambda service (EKS-DX Service) that mimics the real EKS API surface for cluster management and pod identity associations.

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│  AWS Account                                                         │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐    │
│  │ Lambda: EKS-DX Service (mimics EKS API)                      │    │
│  │                                                              │    │
│  │ Cluster Management (DescribeCluster-compatible):             │    │
│  │   POST /clusters                     (RegisterCluster)       │    │
│  │   GET  /clusters/{name}              (DescribeCluster)       │    │
│  │   DELETE /clusters/{name}            (DeregisterCluster)     │    │
│  │   GET  /clusters                     (ListClusters)          │    │
│  │                                                              │    │
│  │ Pod Identity Associations (EKS API-compatible):              │    │
│  │   POST /clusters/{name}/pod-identity-associations            │    │
│  │   GET  /clusters/{name}/pod-identity-associations            │    │
│  │   GET  /clusters/{name}/pod-identity-associations/{id}       │    │
│  │   DELETE /clusters/{name}/pod-identity-associations/{id}     │    │
│  │                                                              │    │
│  │ Auth (called by in-cluster proxy):                           │    │
│  │   POST /clusters/{name}/assets                               │    │
│  │   • Receives pre-validated claims from proxy                 │    │
│  │   • Looks up association in DynamoDB                          │    │
│  │   • STS AssumeRole → returns temporary credentials           │    │
│  └──────────────┬───────────────────────┬───────────────────────┘    │
│                 │                       │                            │
│  ┌──────────────▼──────┐  ┌─────────────▼──────┐                    │
│  │ DynamoDB             │  │ STS                 │                    │
│  │ eks-dx-clusters      │  │ AssumeRole           │                    │
│  │ eks-dx-associations  │  │ + TagSession         │                    │
│  └──────────────────────┘  └────────────────────┘                    │
│                                                                      │
│  API Gateway (HTTPS)                                                 │
└──────────────────────────────────────────────────────────────────────┘
          ▲
          │ POST /clusters/{name}/assets
          │ (pre-validated claims + token metadata)
          │
┌─────────┼────────────────────────────────────────────────────────────┐
│  k3s / microk8s / EKS-D cluster                                      │
│         │                                                            │
│  ┌──────┴──────────────────┐                                        │
│  │ EKS Pod Identity Agent  │                                        │
│  │ --endpoint <proxy-url>  │                                        │
│  │ 169.254.170.23:80       │                                        │
│  └──────────┬──────────────┘                                        │
│             │                                                        │
│  ┌──────────▼──────────────┐                                        │
│  │ eks-dx-auth-proxy           │                                        │
│  │ (lightweight, in-cluster)│                                        │
│  │                          │                                        │
│  │ 1. Kubernetes TokenReview│ ← validates JWT signature + audience  │
│  │ 2. Extract claims        │ ← namespace, SA, pod name/uid        │
│  │ 3. Forward to Lambda     │ ← POST /clusters/{name}/assets       │
│  │    with validated claims │                                        │
│  └──────────────────────────┘                                        │
│                                                                      │
│  ┌──────────────────────────┐                                        │
│  │ eks-dx-pod-identity-webhook │                                        │
│  │ queries Lambda API for   │                                        │
│  │ association existence    │                                        │
│  └──────────────────────────┘                                        │
└──────────────────────────────────────────────────────────────────────┘
```

## Why Two Tiers

| Concern | In-cluster proxy | Lambda (EKS-DX Service) |
|---------|-----------------|------------------------|
| Token validation | ✅ Only the cluster can validate its own SA tokens via TokenReview | ❌ Would need JWKS sync + JWT parsing |
| STS AssumeRole | ❌ Requires broker credentials on the node | ✅ Lambda execution role, no creds on node |
| Association storage | ❌ CRDs are cluster-local, not centrally managed | ✅ DynamoDB, multi-cluster, AWS CLI compatible |
| Cluster registration | ❌ Not applicable | ✅ DescribeCluster-compatible API |
| AWS CLI compatibility | ❌ Custom API | ✅ `aws eks describe-cluster`, `aws eks create-pod-identity-association` work |

The proxy does what only the cluster can do (TokenReview). The Lambda does what should be centralized (storage, STS, management API).

**Bonus**: No JWKS sync needed. The cluster validates its own tokens — no need to push JWKS to DynamoDB. The Lambda trusts the proxy's validated claims.

## EKS-DX Service API (Lambda)

### Cluster Management

Compatible with AWS EKS API surface so `aws eks` CLI commands work with `--endpoint-url`:

```bash
# Register a cluster
aws eks create-cluster \
  --name my-k3s \
  --endpoint-url https://eks-dx.example.com \
  --kubernetes-network-config '{}' \
  --role-arn arn:aws:iam::123456789012:role/unused \
  --resources-vpc-config '{}'

# Describe
aws eks describe-cluster --name my-k3s --endpoint-url https://eks-dx.example.com

# List
aws eks list-clusters --endpoint-url https://eks-dx.example.com

# Deregister
aws eks delete-cluster --name my-k3s --endpoint-url https://eks-dx.example.com
```

### Pod Identity Associations

```bash
# Create association
aws eks create-pod-identity-association \
  --cluster-name my-k3s \
  --namespace default \
  --service-account my-app \
  --role-arn arn:aws:iam::123456789012:role/my-app \
  --endpoint-url https://eks-dx.example.com

# List
aws eks list-pod-identity-associations \
  --cluster-name my-k3s \
  --endpoint-url https://eks-dx.example.com

# Describe
aws eks describe-pod-identity-association \
  --cluster-name my-k3s \
  --association-id assoc-xxx \
  --endpoint-url https://eks-dx.example.com

# Delete
aws eks delete-pod-identity-association \
  --cluster-name my-k3s \
  --association-id assoc-xxx \
  --endpoint-url https://eks-dx.example.com
```

### Auth Endpoint (Internal — called by in-cluster proxy)

```
POST /clusters/{clusterName}/assets
Body: {
  "claims": {
    "namespace": "default",
    "serviceAccount": "my-app",
    "serviceAccountUid": "uid-123",
    "podName": "my-app-abc123",
    "podUid": "pod-uid-456",
    "subject": "system:serviceaccount:default:my-app"
  }
}

Response: {
  "credentials": { "accessKeyId": "...", "secretAccessKey": "...", "sessionToken": "...", "expiration": ... },
  "assumedRoleUser": { "arn": "...", "assumeRoleId": "..." },
  "podIdentityAssociation": { "associationArn": "...", "associationId": "..." },
  "subject": { "namespace": "default", "serviceAccount": "my-app" },
  "audience": "pods.eks.amazonaws.com"
}
```

The proxy forwards the raw token, not pre-validated claims. The Lambda validates it independently via JWKS.

## In-Cluster Proxy (Simplified)

The proxy does TokenReview as a fast-fail, then forwards the raw token to the Lambda:

```java
@POST
@Path("/{clusterName}/assets")
public Response assumeRoleForPodIdentity(
        @PathParam("clusterName") String clusterName,
        AgentRequest request) {

    // 1. Fast-fail: validate token via Kubernetes TokenReview
    //    Rejects expired/malformed tokens before they hit the network
    tokenValidationService.validateToken(request.token, clusterName);

    // 2. Forward raw token to EKS-DX Lambda (Lambda does its own JWKS validation)
    Response lambdaResponse = eksDxClient.assumeRole(clusterName, request.token);

    // 3. Return credentials to agent
    return lambdaResponse;
}
```

No DynamoDB, no STS, no association lookup. Just TokenReview + HTTP forward.

### Proxy IAM Requirements

None. The proxy doesn't call any AWS APIs. It:
- Calls the Kubernetes TokenReview API (via ServiceAccount RBAC)
- Calls the Lambda endpoint (HTTPS)

The EC2 instance profile can be empty or removed entirely.

## Component Summary

| Component | Location | Responsibilities | AWS Permissions |
|-----------|----------|-----------------|-----------------|
| **EKS-DX Service** | Lambda | JWKS token validation, association lookup, STS AssumeRole, cluster + association CRUD | DynamoDB read/write, STS AssumeRole |
| **eks-dx-auth-proxy** | In-cluster | TokenReview (fast-fail), forward raw token to Lambda | None (Kubernetes RBAC only) |
| **eks-dx-pod-identity-webhook** | In-cluster | Pod mutation (inject env vars + token volume), association check via Lambda | None (uses projected SA token to auth) |
| **EKS Pod Identity Agent** | In-cluster (DaemonSet) | Intercepts 169.254.170.23, forwards to proxy | None |
| **eks-dx CLI** | Developer machine | Cluster registration, association management | Calls Lambda API via HTTPS |

## Authentication to Lambda

All in-cluster components authenticate to the Lambda using **Kubernetes SA tokens validated via JWKS**. No API keys, no IAM credentials, no secrets in the cluster.

### Token Audiences

Different audiences distinguish credential exchange from management queries:

| Caller | Endpoint | Token audience | Lambda checks |
|--------|----------|---------------|---------------|
| **Proxy** (on behalf of pod) | `POST /clusters/{name}/assets` | `pods.eks.amazonaws.com` | Valid signature + audience + expiry → look up association → STS AssumeRole |
| **Webhook** | `GET /clusters/{name}/pod-identity-associations` | `eks-dx.plasticity.cloud` | Valid signature + audience + subject is `system:serviceaccount:kube-system:eks-dx-pod-identity-webhook` |

### Proxy → Lambda (credential exchange)

The proxy forwards the pod's raw SA token. The Lambda validates it using the cluster's JWKS from DynamoDB. The token IS the authentication — cryptographically signed by the cluster's kube-apiserver, short-lived, audience-bound. This is exactly how the real EKS Auth Service works.

### Webhook → Lambda (association check)

The webhook mounts a projected SA token with audience `eks-dx.plasticity.cloud`:

```yaml
volumes:
- name: eks-dx-token
  projected:
    sources:
    - serviceAccountToken:
        audience: eks-dx.plasticity.cloud
        expirationSeconds: 3600
        path: token
```

It sends this token as a Bearer header when querying associations:

```
GET /clusters/my-k3s/pod-identity-associations?namespace=default&serviceAccount=my-app
Authorization: Bearer <webhook-sa-token>
```

The Lambda:
1. Validates JWT signature via JWKS (same mechanism as credential exchange)
2. Checks audience = `eks-dx.plasticity.cloud` (not `pods.eks.amazonaws.com`)
3. Checks subject = `system:serviceaccount:kube-system:eks-dx-pod-identity-webhook`
4. Returns associations

### Why This Works

- **No secrets in the cluster** — tokens are generated by the kube-apiserver, not stored
- **No API keys to rotate** — tokens are short-lived (1h default)
- **Same trust mechanism** for both paths — JWKS validation
- **Scoped by audience** — a pod token (`pods.eks.amazonaws.com`) can't query the management API, and a webhook token (`eks-dx.plasticity.cloud`) can't exchange credentials
- **Scoped by subject** — only the webhook SA can query associations, not any SA with the right audience

## DynamoDB Schema

### Table: `eks-dx-clusters`

| PK (clusterName) | issuer | jwks | status | registeredAt |
|-------------------|--------|------|--------|--------------|
| `my-k3s` | `https://kubernetes.default.svc` | `{"keys":[...]}` | `ACTIVE` | `2026-04-26T22:00:00Z` |

### Table: `eks-dx-associations`

| PK | SK | roleArn | associationId | createdAt |
|----|-----|---------|---------------|-----------|
| `CLUSTER#my-k3s` | `default#my-app` | `arn:aws:iam::...:role/my-app` | `assoc-abc123` | `2026-04-26T22:00:00Z` |

## Cost Estimate

Uses API Gateway **HTTP API** (not REST API) — 10x cheaper, sufficient for this use case.

### Single cluster (1,000 credential requests/day)

| Resource | Pricing | Monthly cost |
|----------|---------|-------------|
| API Gateway HTTP API | $1.00 / million requests | $0.03 |
| Lambda (512MB, ~200ms avg) | $0.20 / million requests + compute | $0.02 |
| DynamoDB on-demand | $1.25 / million reads, $1.25 / million writes | $0.04 |
| In-cluster proxy (tiny pod) | Runs on existing node | $0.00 |
| **Total** | | **~$0.09/month** |

### 10 clusters (10,000 requests/day each)

| Resource | Monthly cost |
|----------|-------------|
| API Gateway | $3.00 |
| Lambda | $2.00 |
| DynamoDB | $4.00 |
| **Total** | **~$9/month** |

### Comparison

| Solution | Cost per cluster/month |
|----------|----------------------|
| **EKS-DX (Lambda + DynamoDB)** | ~$0.09 |
| **EKS managed control plane** | $73.00 |
| **Savings** | **99.9%** |

All services are pay-per-request with no minimum. A cluster with zero traffic costs $0.

## What This Eliminates

- ❌ JWKS sync agent (JWKS pushed once at registration, refreshed via `eks-dx update cluster --refresh-jwks`)
- ❌ EC2 instance profile permissions (proxy has no AWS permissions)
- ❌ DynamoDB access from inside the cluster (webhook uses SA token to auth to Lambda)
- ❌ API keys or secrets in the cluster (SA tokens are the authentication)
- ❌ CRD resources (replaced by DynamoDB)
- ❌ Custom CLI for associations (replaced by `eks-dx` CLI)
