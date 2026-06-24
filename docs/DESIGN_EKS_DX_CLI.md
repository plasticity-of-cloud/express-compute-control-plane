# eks-dx CLI Design

## Overview

Native binary CLI (Quarkus + Picocli + GraalVM) that provides an EKS-like UX for managing EKS-DX clusters and pod identity associations. Uses Fabric8 Kubernetes Client for cluster introspection and JDK `java.net.http.HttpClient` for Lambda API calls.

## Command Structure

```
eks-dx <verb> <resource> [flags]
```

Mirrors `eksctl` / `aws eks` verb-resource pattern.

### Cluster Management

```bash
# Register — auto-reads JWKS + issuer from current kubeconfig context
eks-dx register-cluster --name my-k3s --region us-east-1

# Describe
eks-dx describe-cluster --name my-k3s

# List
eks-dx list-clusters

# Refresh JWKS (after SA key rotation)
eks-dx update-cluster --name my-k3s --refresh-jwks

# Deregister
eks-dx deregister-cluster --name my-k3s
```

### Pod Identity Associations

```bash
# Create — same flags as aws eks create-pod-identity-association
eks-dx create-association \
  --cluster-name my-k3s \
  --namespace default \
  --service-account my-app \
  --role-arn arn:aws:iam::123456789012:role/my-app

# List
eks-dx list-associations --cluster-name my-k3s

# Describe
eks-dx describe-association \
  --cluster-name my-k3s \
  --association-id assoc-abc123

# Delete
eks-dx delete-association \
  --cluster-name my-k3s \
  --association-id assoc-abc123
```

## `create cluster` Flow

The key UX: one command, auto-detects everything from kubeconfig.

```java
@Command(name = "cluster")
public class CreateClusterCommand implements Runnable {

    @Inject
    KubernetesClient kubernetesClient;  // Fabric8 — reads ~/.kube/config

    @Option(names = "--name", required = true)
    String clusterName;

    @Option(names = "--region", required = true)
    String region;

    @Option(names = "--endpoint", defaultValue = "https://eks-dx.codriverlabs.ai")
    String eksDxEndpoint;

    @Override
    public void run() {
        // 1. Read JWKS from kube-apiserver (Fabric8 raw request)
        String jwks = kubernetesClient.raw("/openid/v1/jwks");

        // 2. Read issuer from OIDC discovery
        String oidcConfig = kubernetesClient.raw("/.well-known/openid-configuration");
        String issuer = parseIssuer(oidcConfig);

        // 3. Register with EKS-DX Lambda API (JDK HttpClient)
        var request = HttpRequest.newBuilder()
            .uri(URI.create(eksDxEndpoint + "/clusters"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.object()
                .put("name", clusterName)
                .put("region", region)
                .put("issuer", issuer)
                .put("jwks", jwks)
                .toString()))
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // 4. Print result
        System.out.printf("✓ Cluster \"%s\" registered%n", clusterName);
        System.out.printf("✓ JWKS stored (%d keys)%n", countKeys(jwks));
        System.out.println();
        System.out.println("Next steps:");
        System.out.printf("  helm install eks-pod-identity-agent ... \\%n");
        System.out.printf("    --set \"agent.additionalArgs.--endpoint=%s/clusters/%s/assets\"%n",
            eksDxEndpoint, clusterName);
    }
}
```

## Dependencies

| Dependency | Purpose | Why this one |
|-----------|---------|-------------|
| Fabric8 Kubernetes Client | Read JWKS, OIDC config from kube-apiserver | Already in the project, handles kubeconfig/TLS/auth |
| `java.net.http.HttpClient` | Call EKS-DX Lambda API | JDK built-in, zero extra dependencies, GraalVM-friendly |
| Picocli (via `quarkus-picocli`) | CLI framework | Already in the project |

No AWS SDK in the CLI. All AWS interactions go through the Lambda API.

## Module Structure

```
eks-dx-cli/
  src/main/java/ai/codriverlabs/eksdx/cli/
    EksDxCommand.java                          # Top-level command group
    cluster/
      CreateClusterCommand.java                # create cluster
      DescribeClusterCommand.java              # describe cluster
      ListClustersCommand.java                 # list clusters
      UpdateClusterCommand.java                # update cluster --refresh-jwks
      DeleteClusterCommand.java                # delete cluster
    association/
      CreateAssociationCommand.java            # create pod-identity-association
      ListAssociationsCommand.java             # list pod-identity-associations
      DescribeAssociationCommand.java          # describe pod-identity-association
      DeleteAssociationCommand.java            # delete pod-identity-association
    util/
      EksDxApiClient.java                      # JDK HttpClient wrapper for Lambda API
      OutputFormatter.java                     # Consistent output formatting
```

## Build

```bash
# Native binary (GraalVM container build)
mvn -pl eks-dx-cli package -Pnative

# Produces: target/eks-dx-cli-*-runner → rename to eks-dx
```

## Configuration

The CLI needs to know the EKS-DX service endpoint. Resolution order:

1. `--endpoint` flag
2. `EKS_DX_ENDPOINT` environment variable
3. `~/.eks-dx/config` file
4. Default: `https://eks-dx.codriverlabs.ai`

```bash
# Or configure once:
eks-dx configure --endpoint https://eks-dx.us-east-1.codriverlabs.ai --region us-east-1
```
