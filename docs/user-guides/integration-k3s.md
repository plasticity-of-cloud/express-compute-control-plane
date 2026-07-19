# Express Compute Integration: k3s on EC2

End-to-end procedure to enable EKS Workload Identity on a k3s cluster.

> For a full tutorial including EC2 launch and setup from scratch, see [ec2-k3s-pod-identity/](ec2-k3s-pod-identity/).

## Prerequisites

- k3s node running on EC2 (single-node or multi-node)
- `ecp` CLI installed locally
- AWS account with CDK stack deployed (see [deployment.md](deployment.md))
- `ENDPOINT` env var set to your API Gateway URL

---

## 1. Configure k3s to expose OIDC

k3s must be started with a public issuer URL so the JWKS can be registered.

On the k3s node:

```bash
# Get the node's public IP
PUBLIC_IP=$(curl -s http://169.254.169.254/latest/meta-data/public-ipv4)

# Install k3s with OIDC issuer set to the public IP
curl -sfL https://get.k3s.io | sh -s - \
  --kube-apiserver-arg="service-account-issuer=https://${PUBLIC_IP}" \
  --kube-apiserver-arg="service-account-jwks-uri=https://${PUBLIC_IP}/openid/v1/jwks"
```

If k3s is already running, add to `/etc/rancher/k3s/config.yaml`:

```yaml
kube-apiserver-arg:
  - "service-account-issuer=https://<PUBLIC_IP>"
  - "service-account-jwks-uri=https://<PUBLIC_IP>/openid/v1/jwks"
```

Then restart: `systemctl restart k3s`

---

## 2. Register the cluster with ecp

From your local machine (with `~/.kube/config` pointing at the k3s cluster):

```bash
# Configure CLI endpoint once
ecp configure --endpoint $ENDPOINT --region us-east-1

# Register — auto-discovers issuer + JWKS from the kube-apiserver
ecp create-cluster --name my-k3s --oidc-mode self-managed
```

Or manually if auto-discovery doesn't work (e.g. kube-apiserver not reachable from your machine):

```bash
# On the k3s node
kubectl get --raw /openid/v1/jwks > /tmp/jwks.json

# From your machine
ecp create-cluster --name my-k3s --oidc-mode self-managed \
  --issuer https://<PUBLIC_IP> \
  --jwks-file /tmp/jwks.json
```

Verify:

```bash
ecp describe-cluster my-k3s
```

---

## 3. Install Express Compute Workload Identity components

A single script installs all three components (auth-proxy, webhook, pod-identity-agent):

```bash
curl -sL https://github.com/plasticity-of-cloud/express-compute/releases/latest/download/install-ecp-pod-identity.sh \
  | CLUSTER_NAME=my-k3s AWS_REGION=us-east-1 ECP_ENDPOINT=${ENDPOINT} bash
```

If your EC2 node has an instance profile, the proxy picks up AWS credentials automatically via the metadata service. Otherwise export them before running:

```bash
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...
```

---

## 4. Create a workload identity

First, prepare your IAM role (see [IAM Role Setup](iam/iam-role-setup.md) for full details):

```bash
# Tag the role so Express Compute can manage the trust policy automatically
aws iam tag-role --role-name my-role --tags Key=ecp-managed,Value=true
```

Then create the association:

```bash
ecp create-association \
  --cluster-name my-k3s \
  --namespace my-app \
  --service-account my-sa \
  --role-arn arn:aws:iam::123456789012:role/my-role
```

Express Compute automatically configures the role's trust policy with the correct scoped statement (cluster + namespace + service account). No role naming constraints apply.

---

## 5. Test

Deploy a pod using the associated service account:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-sa
  namespace: my-app
---
apiVersion: v1
kind: Pod
metadata:
  name: aws-test
  namespace: my-app
spec:
  serviceAccountName: my-sa
  containers:
  - name: aws-cli
    image: amazon/aws-cli:latest
    command: ["aws", "sts", "get-caller-identity"]
```

```bash
kubectl apply -f test-pod.yaml
kubectl logs aws-test -n my-app
# Expected: JSON with the assumed role ARN
```

---

## Troubleshooting

**TokenReview fails:** the proxy can't reach the kube-apiserver. Check `KUBERNETES_SERVICE_HOST` is reachable from `kube-system`.

**JWT validation fails:** the issuer in DynamoDB doesn't match the `iss` claim in the SA token. Re-register with the correct `--issuer`.

**No association found:** the pod's `namespace/serviceAccount` doesn't match any registered association. Run `ecp list-associations --cluster my-k3s`.

**Proxy token rejected:** the proxy's projected SA token audience doesn't match `ecp.codriverlabs.ai`. Check the volume spec in the proxy deployment.
