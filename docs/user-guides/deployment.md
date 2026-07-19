# Express Compute Deployment Guide

## Architecture overview

```
┌─────────────────────────────────────────────────────────┐
│  AWS (Lambda + API Gateway + DynamoDB)                  │
│                                                         │
│  ecp-credential-service  ← POST /clusters/*/assets  │
│  ecp-mgmt-service        ← /clusters, /associations  │
│  ecp-tenant-service      ← /clusters, /tenants (+ SSE)  │
│                                                         │
│  ecp-clusters   (DynamoDB)                           │
│  ecp-workload-identities (DynamoDB)                         │
│  ecp-tenants    (DynamoDB)                           │
└─────────────────────────────────────────────────────────┘
         ▲ HTTPS (API Gateway)
         │
┌────────┴──────────────────────────────────────────────┐
│  Kubernetes cluster (k3s / microk8s / Express Compute)   │
│                                                        │
│  ecp-auth-proxy      (kube-system)                  │
│    - Kubernetes TokenReview fast-fail                  │
│    - Forwards to credential-service with proxy token   │
│                                                        │
│  ecp-workload-identity-webhook  (kube-system)            │
│    - Injects AWS_CONTAINER_CREDENTIALS_FULL_URI        │
│    - Injects projected SA token volume into pods       │
└────────────────────────────────────────────────────────┘
```

---

## Artifacts

| Artifact | Built from | Deployed as |
|---|---|---|
| `ecp-credential-service/target/function.zip` | Maven | Lambda (SnapStart) |
| `ecp-mgmt-service/target/function.zip` | Maven | Lambda |
| `ecp-tenant-service/target/function.zip` | Maven | Lambda + Function URL |
| `ecp-auth-proxy` image | Maven + Jib | Kubernetes Deployment |
| `ecp-workload-identity-webhook` image | Maven + Jib | Kubernetes Deployment |
| `ecp-cli` native binary | GraalVM | Local / CI/CD |

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

Output: `ecp-{credential,mgmt,tenant}-service/target/function.zip`

### Container images

```bash
REGISTRY=ghcr.io/codriverlabs   # or your ECR registry
VERSION=1.0.0

mvn -pl ecp-auth-proxy package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dquarkus.container-image.registry=${REGISTRY} \
  -Dquarkus.container-image.tag=${VERSION}

mvn -pl ecp-workload-identity-webhook package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dquarkus.container-image.registry=${REGISTRY} \
  -Dquarkus.container-image.tag=${VERSION}
```

### CLI native binary

```bash
mvn -pl ecp-cli package -Pnative -DskipTests
# Binary: ecp-cli/target/ecp-cli-*-runner
sudo cp ecp-cli/target/ecp-cli-*-runner /usr/local/bin/ecp
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
cd infra && cdk deploy ExpressComputeControlPlaneStack
```

After deploy, capture the outputs:

```bash
ENDPOINT=$(aws cloudformation describe-stacks --stack-name ExpressComputeControlPlaneStack \
  --query 'Stacks[0].Outputs[?OutputKey==`ApiEndpoint`].OutputValue' \
  --output text)
  --output text)

STREAM_URL=$(aws cloudformation describe-stacks --stack-name ecp \
  --query 'Stacks[0].Outputs[?OutputKey==`TenantStreamFunctionUrl`].OutputValue' \
  --output text)
```

---

## 3. Register the cluster

The cluster's OIDC issuer and JWKS must be registered once. The CLI auto-discovers them from the kube-apiserver.

```bash
ecp configure --endpoint $ENDPOINT --region us-east-1

# Auto-discovers issuer + JWKS from /.well-known/openid-configuration
ecp create-cluster --oidc-mode self-managed --name my-cluster
```

Or explicitly:

```bash
ecp create-cluster --oidc-mode self-managed --name my-cluster \
  --issuer https://<public-ip-or-domain> \
  --jwks-file /tmp/jwks.json
```

---

## 4. Install in-cluster components

### Helm (recommended)

```bash
VERSION=1.0.0
REGISTRY=ghcr.io/codriverlabs

helm install ecp-auth-proxy \
  oci://${REGISTRY}/helm/ecp-auth-proxy \
  --version ${VERSION} \
  --namespace kube-system \
  --set app.imageConfig.registry=${REGISTRY} \
  --set app.imageConfig.tag=${VERSION} \
  --set app.envs.ECP_ENDPOINT=${ENDPOINT}

helm install ecp-workload-identity-webhook \
  oci://${REGISTRY}/helm/ecp-workload-identity-webhook \
  --version ${VERSION} \
  --namespace kube-system \
  --set app.imageConfig.registry=${REGISTRY} \
  --set app.imageConfig.tag=${VERSION} \
  --set app.envs.ECP_ENDPOINT=${ENDPOINT} \
  --set app.envs.EKS_CLUSTER_NAME=my-cluster
```

### kubectl (raw manifests)

```bash
kubectl apply -f deploy/ecp-auth-proxy.yaml
kubectl apply -f ecp-workload-identity-webhook/k8s/
```

---

## 5. Create a workload identity

See [IAM Role Setup](user-guides/iam/iam-role-setup.md) for full details on preparing IAM roles.

```bash
# Tag the role for automatic trust policy management
aws iam tag-role --role-name my-role --tags Key=ecp-managed,Value=true

ecp create-association \
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

ecp create-cluster --oidc-mode self-managed --name my-k3s \
  --issuer https://<node-public-ip> \
  --jwks-file /tmp/jwks.json
```

The auth-proxy needs to reach the kube-apiserver for TokenReview. In k3s this works out of the box since the proxy runs in-cluster.

### Express Compute

Express Compute clusters are provisioned via `ecp create-cluster --name`, which:
1. Launches an EC2 instance with kubeadm
2. Pre-registers the SA signing key in Secrets Manager
3. Runs `kubeadm init` with `--service-account-issuer https://<public-ip>`
4. Auto-registers the cluster with mgmt-service on first boot

No manual cluster registration step is needed — the EC2 instance self-registers. After the tenant reaches `state: ready`:

```bash
# Provision and stream progress; SSH key saved to ~/.express-compute/tenants/ on completion
ecp create-cluster acme-staging --wait

# Retrieve connection details at any time (including future sessions / other machines)
ecp get-cluster-access acme-staging
#   Cluster:    acme-staging
#   Public IP:  54.12.34.56
#   SSH key:    ~/.express-compute/tenants/us-east-1/a1b2c3d4.pem
#   Connect:
#   ssh -i ~/.express-compute/tenants/us-east-1/a1b2c3d4.pem ec2-user@54.12.34.56

# If the .pem file was lost, re-fetch from Secrets Manager:
ecp get-cluster-access acme-staging --save-key

# Install in-cluster components on the new cluster
KUBECONFIG=/path/to/acme-staging.kubeconfig \
helm install ecp-auth-proxy oci://${REGISTRY}/helm/ecp-auth-proxy \
  --namespace kube-system \
  --set app.envs.ECP_ENDPOINT=${ENDPOINT} \
  --set app.envs.EKS_CLUSTER_NAME=acme-staging
```

---

## Proxy token security model

The auth-proxy holds a projected SA token with audience `ecp.codriverlabs.ai` (mounted at `/var/run/secrets/ecp/token`, rotated every hour by Kubernetes). This token is attached as `Authorization: Bearer` on every forwarded request.

The credential-service Lambda validates this token against the cluster's JWKS in DynamoDB **before** processing the pod's credential request. This means:

- Requests from outside the cluster are rejected (no valid proxy token)
- Cross-cluster calls are rejected (cluster-1's proxy token fails against cluster-2's JWKS)
- The Kubernetes TokenReview fast-fail in the proxy cannot be bypassed

---

## Cleanup

```bash
helm uninstall ecp-auth-proxy -n kube-system
helm uninstall ecp-workload-identity-webhook -n kube-system
cd infra && cdk destroy ExpressComputeControlPlaneStack
```

For tenant clusters:
```bash
ecp delete-cluster --name acme-staging   # terminates EC2, removes secrets, deregisters cluster
```
