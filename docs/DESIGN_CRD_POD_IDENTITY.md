# CRD-Based Pod Identity Associations

## Summary

Pod identity associations are stored as Kubernetes CRDs (`PodIdentityAssociation`) instead of requiring an AWS EKS managed cluster. This eliminates the $73/month EKS control plane cost and works on any Kubernetes distribution (k3s, EKS-D, kubeadm).

## Architecture

```
Pod Token → eks-dx-auth-proxy → CRD lookup (local cluster) → STS AssumeRole
                                  ↓ (fallback)
                             generated default role ARN
```

### Lookup Order

1. **CRD** — `PodIdentityAssociation` resource in the pod's namespace
2. **Generated default** — `arn:aws:iam::<account>:role/eks-pod-identity-<ns>-<sa>`

## CRD Definition

```yaml
apiVersion: apiextensions.k8s.io/v1
kind: CustomResourceDefinition
metadata:
  name: podidentityassociations.eks.plasticity.cloud
spec:
  group: eks.plasticity.cloud
  names:
    kind: PodIdentityAssociation
    plural: podidentityassociations
  scope: Namespaced
  versions:
  - name: v1
    served: true
    storage: true
    schema:
      openAPIV3Schema:
        type: object
        properties:
          spec:
            type: object
            required: [clusterName, namespace, serviceAccount, roleArn]
            properties:
              clusterName:
                type: string
              namespace:
                type: string
              serviceAccount:
                type: string
              roleArn:
                type: string
```

CRD naming convention: `{clusterName}-{serviceAccount}` in the target namespace.

## CLI Management

Associations are managed via the `eks-d-auth-cli` native binary (separate module, Quarkus + Picocli):

```bash
CLI=./eks-d-auth-cli/target/eks-d-auth-cli-*-runner

$CLI create  --cluster-name k3s-pod-id --service-account default:my-app --role-arn arn:aws:iam::123456789012:role/my-role
$CLI list    --cluster-name k3s-pod-id
$CLI describe --cluster-name k3s-pod-id --service-account default:my-app
$CLI delete  --cluster-name k3s-pod-id --service-account default:my-app
```

The `--service-account` format is `namespace:serviceaccount`.

## Module Structure

```
eks-pod-identity-crd/          # Shared CRD model (PodIdentityAssociation, PodIdentityAssociationSpec)
eks-d-auth-cli/                # CLI tool (native binary via GraalVM container build)
eks-dx-auth-proxy/                # Proxy service (CRD lookup in PodIdentityAssociationService)
eks-dx-pod-identity-webhook/      # Webhook (CRD lookup in PodIdentityAssociationLookup)
```

## RBAC

Both the proxy and webhook need cluster-wide CRD read access:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: eks-dx-auth-proxy
rules:
- apiGroups: ["authentication.k8s.io"]
  resources: ["tokenreviews"]
  verbs: ["create"]
- apiGroups: ["eks.plasticity.cloud"]
  resources: ["podidentityassociations"]
  verbs: ["get", "list", "watch"]
```

## Phase 3: CloudFormation Support (Not Implemented)

Future work: parse `AWS::EKS::PodIdentityAssociation` resources from CloudFormation templates and create CRDs. Not currently planned.
