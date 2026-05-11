# EKS-DX Deployment Guide

Two deployment paths:

1. **[GitHub Release artifacts](#github-release-artifacts)** — pre-built binaries, images, and Helm charts from a tagged release
2. **[Local build](#local-build)** — build everything from source

---

## GitHub Release Artifacts

Each `v*` tag publishes to:
- **GHCR** (`ghcr.io/plasticity-of-cloud/`) — Docker images and Helm charts (OCI), **free for public repos, no auth required to pull**
- **GitHub Releases** — CLI binaries, Lambda zip, SAM template, Helm chart tarballs

| Artifact | Location |
|----------|----------|
| Docker images | `ghcr.io/plasticity-of-cloud/eks-dx-auth-proxy:<version>` |
| | `ghcr.io/plasticity-of-cloud/eks-dx-pod-identity-webhook:<version>` |
| Helm charts (OCI) | `oci://ghcr.io/plasticity-of-cloud/helm/eks-dx-auth-proxy` |
| | `oci://ghcr.io/plasticity-of-cloud/helm/eks-dx-pod-identity-webhook` |
| Helm charts (tarball) | GitHub Releases `eks-dx-auth-proxy-<version>.tar.gz` |
| Lambda zip | GitHub Releases `eks-dx-lambda-<version>.zip` |
| SAM template | GitHub Releases `eks-dx-sam-<version>.tar.gz` |
| CLI binary | GitHub Releases `eks-dx-cli-<version>-linux-{amd64,arm64}` |

### 1. Download release assets

```bash
VERSION=1.0.0
ARCH=arm64   # or amd64
BASE=https://github.com/plasticity-of-cloud/eks-dx-control-plane/releases/download/v${VERSION}

# CLI
curl -Lo eks-dx ${BASE}/eks-dx-cli-${VERSION}-linux-${ARCH}
chmod +x eks-dx && sudo mv eks-dx /usr/local/bin/

# Lambda + SAM
curl -LO ${BASE}/eks-dx-lambda-${VERSION}.zip
curl -LO ${BASE}/eks-dx-sam-${VERSION}.tar.gz && tar xzf eks-dx-sam-${VERSION}.tar.gz
```

### 2. Deploy the Lambda backend

```bash
sam deploy -t sam.yaml --stack-name eks-dx --region us-east-1 \
  --capabilities CAPABILITY_IAM --resolve-s3 --no-confirm-changeset \
  --parameter-overrides LambdaZip=eks-dx-lambda-${VERSION}.zip
```

Save the API Gateway endpoint from the stack outputs:
```bash
ENDPOINT=$(aws cloudformation describe-stacks --stack-name eks-dx \
  --query 'Stacks[0].Outputs[?OutputKey==`ApiEndpoint`].OutputValue' --output text)
```

### 3. Register the cluster

```bash
eks-dx configure --endpoint $ENDPOINT --region us-east-1
eks-dx create cluster --name my-k3s --region us-east-1
```

### 4. Install Helm charts from GHCR

No registry login required for public images.

```bash
helm install eks-dx-auth-proxy oci://ghcr.io/plasticity-of-cloud/helm/eks-dx-auth-proxy \
  --version ${VERSION} \
  --namespace kube-system \
  --set app.imageConfig.registry=ghcr.io \
  --set app.imageConfig.repository=plasticity-of-cloud/eks-dx-auth-proxy \
  --set app.imageConfig.tag=${VERSION} \
  --set app.envs.EKS_DX_ENDPOINT=${ENDPOINT}

helm install eks-dx-pod-identity-webhook oci://ghcr.io/plasticity-of-cloud/helm/eks-dx-pod-identity-webhook \
  --version ${VERSION} \
  --namespace kube-system \
  --set app.imageConfig.registry=ghcr.io \
  --set app.imageConfig.repository=plasticity-of-cloud/eks-dx-pod-identity-webhook \
  --set app.imageConfig.tag=${VERSION} \
  --set app.envs.EKS_DX_ENDPOINT=${ENDPOINT} \
  --set app.envs.EKS_CLUSTER_NAME=my-k3s
```

Or from the release tarball:

```bash
curl -LO ${BASE}/eks-dx-auth-proxy-${VERSION}.tar.gz
helm install eks-dx-auth-proxy eks-dx-auth-proxy-${VERSION}.tar.gz \
  --namespace kube-system \
  --set app.imageConfig.registry=ghcr.io \
  --set app.imageConfig.tag=${VERSION} \
  --set app.envs.EKS_DX_ENDPOINT=${ENDPOINT}
```

---

## Local Build

### Prerequisites

- Java 21+, Maven 3.8+
- Docker (for container images)
- GraalVM 21 (for native CLI only)
- AWS CLI v2, SAM CLI

### 1. Build everything

```bash
# Tests + all modules
mvn verify

# Lambda zip
mvn -pl eks-dx-lambda package -DskipTests

# CLI native binary
mvn -pl eks-dx-cli package -Pnative -DskipTests
# Binary: eks-dx-cli/target/eks-dx-cli-*-runner

# Container images + Helm charts
REGISTRY=864899852480.dkr.ecr.us-east-1.amazonaws.com
VERSION=1.0.0-SNAPSHOT

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

# Helm chart tarballs are at:
#   eks-dx-auth-proxy/target/helm/kubernetes/eks-dx-auth-proxy-*.tar.gz
#   eks-dx-pod-identity-webhook/target/helm/kubernetes/eks-dx-pod-identity-webhook-*.tar.gz
```

### 2. Deploy Lambda backend

```bash
mvn -pl eks-dx-lambda package -DskipTests
sam deploy -t sam.yaml --stack-name eks-dx --region us-east-1 \
  --capabilities CAPABILITY_IAM --resolve-s3 --no-confirm-changeset

ENDPOINT=$(aws cloudformation describe-stacks --stack-name eks-dx \
  --query 'Stacks[0].Outputs[?OutputKey==`ApiEndpoint`].OutputValue' --output text)
```

### 3. Register the cluster

```bash
CLI="java -jar eks-dx-cli/target/eks-dx-cli-*-runner.jar"
$CLI configure --endpoint $ENDPOINT --region us-east-1
$CLI create cluster --name my-k3s --region us-east-1
```

### 4. Install Helm charts

```bash
helm install eks-dx-auth-proxy \
  eks-dx-auth-proxy/target/helm/kubernetes/eks-dx-auth-proxy-*.tar.gz \
  --namespace kube-system \
  --set app.imageConfig.registry=${REGISTRY} \
  --set app.imageConfig.tag=${VERSION} \
  --set app.envs.EKS_DX_ENDPOINT=${ENDPOINT}

helm install eks-dx-pod-identity-webhook \
  eks-dx-pod-identity-webhook/target/helm/kubernetes/eks-dx-pod-identity-webhook-*.tar.gz \
  --namespace kube-system \
  --set app.imageConfig.registry=${REGISTRY} \
  --set app.imageConfig.tag=${VERSION} \
  --set app.envs.EKS_DX_ENDPOINT=${ENDPOINT} \
  --set app.envs.EKS_CLUSTER_NAME=my-k3s
```

### 5. Integration tests

```bash
# Start DynamoDB Local
docker run -d -p 18000:8000 public.ecr.aws/aws-dynamodb-local/aws-dynamodb-local:latest

mvn verify -Dintegration.dynamodb=true
```

---

## Helm Image Values Reference

Both charts expose the following values for image configuration:

```yaml
app:
  imageConfig:
    registry: 864899852480.dkr.ecr.us-east-1.amazonaws.com  # override per environment
    repository: plcloud/eks-dx-auth-proxy                    # or eks-dx-pod-identity-webhook
    tag: 1.0.0
```

Override at install time:
```bash
helm upgrade eks-dx-auth-proxy <chart> \
  --set app.imageConfig.registry=<registry> \
  --set app.imageConfig.tag=<new-version>
```

## Cleanup

```bash
helm uninstall eks-dx-auth-proxy -n kube-system
helm uninstall eks-dx-pod-identity-webhook -n kube-system
sam delete --stack-name eks-dx
```
