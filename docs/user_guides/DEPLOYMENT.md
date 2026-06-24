# EKS-DX Deployment Guide

## Architecture overview

```
┌─────────────────────────────────────────────────────────┐
│  AWS (Lambda + API Gateway + DynamoDB)                  │
│                                                         │
│  eks-dx-credential-service  ← POST /clusters/*/assets  │
│  eks-dx-mgmt-service        ← /clusters, /associations  │
│  eks-dx-tenant-service      ← /tenants (+ SSE stream)  │
│                                                         │
│  eks-dx-clusters   (DynamoDB)                           │
│  eks-dx-associations (DynamoDB)                         │
│  eks-dx-tenants    (DynamoDB)                           │
└─────────────────────────────────────────────────────────┘
         ▲ HTTPS (API Gateway)
         │
┌────────┴──────────────────────────────────────────────┐
│  Kubernetes cluster (k3s / microk8s / EKS-D-Xpress)   │
│                                                        │
│  eks-dx-auth-proxy      (kube-system)                  │
│    - Kubernetes TokenReview fast-fail                  │
│    - Forwards to credential-service with proxy token   │
│                                                        │
│  eks-dx-pod-identity-webhook  (kube-system)            │
│    - Injects AWS_CONTAINER_CREDENTIALS_FULL_URI        │
│    - Injects projected SA token volume into pods       │
└────────────────────────────────────────────────────────┘
```

---

## Artifacts

| Artifact | Built from | Deployed as |
|---|---|---|
| `eks-dx-credential-service/target/function.zip` | Maven | Lambda (SnapStart) |
| `eks-dx-mgmt-service/target/function.zip` | Maven | Lambda |
| `eks-dx-tenant-service/target/function.zip` | Maven | Lambda + Function URL |
| `eks-dx-auth-proxy` image | Maven + Jib | Kubernetes Deployment |
| `eks-dx-pod-identity-webhook` image | Maven + Jib | Kubernetes Deployment |
| `eks-dx-cli` native binary | GraalVM | Local / CI/CD |

---

## 1. Build

### Prerequisites
- Java 25, Maven 3.9+
- Docker (for container images and native builds)
- AWS CLI v2, CDK CLI (`npm install -g aws-cdk`)
- AWS credentials with appropriate permissions

### Lambda functions (all three)

```bash
./build-local.sh --only credential,mgmt,tenant --skip-tests

# Or with native tenant-service (production):
./build-local.sh --only credential,mgmt,tenant --native --skip-tests
```

Output: `eks-dx-{credential,mgmt,tenant}-service/target/function.zip`

### Container images

```bash
REGISTRY=ghcr.io/codriverlabs   # or your ECR registry
VERSION=1.0.0

mvn -pl eks-dx-auth-proxy package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dquarkus.container-image.registry=${REGISTRY} \
  -Dquarkus.container-image.tag=${VERSION}

mvn -pl eks-dx-pod-identity-webhook package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dquarkus.container-image.registry=${REGISTRY} \
  -Dquarkus.container-image.tag=${VERSION}
```

### CLI native binary

```bash
mvn -pl eks-dx-cli package -Pnative -DskipTests
# Binary: eks-dx-cli/target/eks-dx-cli-*-runner
sudo cp eks-dx-cli/target/eks-dx-cli-*-runner /usr/local/bin/eks-dx
```

---

## 2. Deploy the Lambda backend (CDK)

```bash
./deploy-local.sh

# Or skip build if zips already exist:
./deploy-local.sh --skip-build
```

Or manually with CDK CLI:

```bash
cd infra && cdk deploy EksDXpressControlPlaneStack
```

After deploy, capture the outputs:

```bash
ENDPOINT=$(aws cloudformation describe-stacks --stack-name EksDXpressControlPlaneStack \
  --query 'Stacks[0].Outputs[?OutputKey==`ApiEndpoint`].OutputValue' \
  --output text)
  --output text)

STREAM_URL=$(aws cloudformation describe-stacks --stack-name eks-dx \
  --query 'Stacks[0].Outputs[?OutputKey==`TenantStreamFunctionUrl`].OutputValue' \
  --output text)
```

---

## 3. Register the cluster

The cluster's OIDC issuer and JWKS must be registered once. The CLI auto-discovers them from the kube-apiserver.

```bash
eks-dx configure --endpoint $ENDPOINT --region us-east-1

# Auto-discovers issuer + JWKS from /.well-known/openid-configuration
eks-dx register-cluster --name my-cluster
```

Or explicitly:

```bash
eks-dx register-cluster --name my-cluster \
  --issuer https://<public-ip-or-domain> \
  --jwks-file /tmp/jwks.json
```

---

## 4. Install in-cluster components

### Helm (recommended)

```bash
VERSION=1.0.0
REGISTRY=ghcr.io/codriverlabs

helm install eks-dx-auth-proxy \
  oci://${REGISTRY}/helm/eks-dx-auth-proxy \
  --version ${VERSION} \
  --namespace kube-system \
  --set app.imageConfig.registry=${REGISTRY} \
  --set app.imageConfig.tag=${VERSION} \
  --set app.envs.EKS_DX_ENDPOINT=${ENDPOINT}

helm install eks-dx-pod-identity-webhook \
  oci://${REGISTRY}/helm/eks-dx-pod-identity-webhook \
  --version ${VERSION} \
  --namespace kube-system \
  --set app.imageConfig.registry=${REGISTRY} \
  --set app.imageConfig.tag=${VERSION} \
  --set app.envs.EKS_DX_ENDPOINT=${ENDPOINT} \
  --set app.envs.EKS_CLUSTER_NAME=my-cluster
```

### kubectl (raw manifests)

```bash
kubectl apply -f deploy/eks-dx-auth-proxy.yaml
kubectl apply -f eks-dx-pod-identity-webhook/k8s/
```

---

## 5. Create a pod identity association

See [IAM Role Setup](user_guides/iam/IAM_ROLE_SETUP.md) for full details on preparing IAM roles.

```bash
# Tag the role for automatic trust policy management
aws iam tag-role --role-name my-role --tags Key=eks-dx-managed,Value=true

eks-dx create-association \
  --cluster-name my-cluster \
  --namespace my-app \
  --service-account my-sa \
  --role-arn arn:aws:iam::123456789012:role/my-role
```

---

## Platform-specific notes

### k3s on EC2

k3s exposes the OIDC discovery endpoint on the kube-apiserver. The issuer is typically `https://<node-public-ip>` (set via `--kube-apiserver-arg=service-account-issuer=https://<ip>`).

```bash
# On the k3s node — extract JWKS
kubectl get --raw /openid/v1/jwks > /tmp/jwks.json

eks-dx register-cluster --name my-k3s \
  --issuer https://<node-public-ip> \
  --jwks-file /tmp/jwks.json
```

The auth-proxy needs to reach the kube-apiserver for TokenReview. In k3s this works out of the box since the proxy runs in-cluster.

### EKS-D-Xpress

EKS-D-Xpress clusters are provisioned via `eks-dx create-tenant`, which:
1. Launches an EC2 instance with kubeadm
2. Pre-registers the SA signing key in Secrets Manager
3. Runs `kubeadm init` with `--service-account-issuer https://<public-ip>`
4. Auto-registers the cluster with eks-dx-lambda on first boot

No manual `eks-dx register-cluster` step is needed — the EC2 instance self-registers. After the tenant reaches `state: ready`:

```bash
# Watch provisioning progress
eks-dx create-tenant acme-staging --wait

# Install in-cluster components on the new cluster
KUBECONFIG=/path/to/acme-staging.kubeconfig \
helm install eks-dx-auth-proxy oci://${REGISTRY}/helm/eks-dx-auth-proxy \
  --namespace kube-system \
  --set app.envs.EKS_DX_ENDPOINT=${ENDPOINT} \
  --set app.envs.EKS_CLUSTER_NAME=acme-staging
```

---

## Proxy token security model

The auth-proxy holds a projected SA token with audience `eks-dx.codriverlabs.ai` (mounted at `/var/run/secrets/eks-dx/token`, rotated every hour by Kubernetes). This token is attached as `Authorization: Bearer` on every forwarded request.

The credential-service Lambda validates this token against the cluster's JWKS in DynamoDB **before** processing the pod's credential request. This means:

- Requests from outside the cluster are rejected (no valid proxy token)
- Cross-cluster calls are rejected (cluster-1's proxy token fails against cluster-2's JWKS)
- The Kubernetes TokenReview fast-fail in the proxy cannot be bypassed

---

## Cleanup

```bash
helm uninstall eks-dx-auth-proxy -n kube-system
helm uninstall eks-dx-pod-identity-webhook -n kube-system
cd infra && cdk destroy EksDXpressControlPlaneStack
```

For tenant clusters:
```bash
eks-dx delete-tenant acme-staging   # terminates EC2, removes secrets, deregisters cluster
```
