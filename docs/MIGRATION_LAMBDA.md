# Migration: eks-dx-auth-proxy → Two-Tier Lambda Architecture

## Scope

Split the current monolithic in-cluster proxy into:
1. **eks-dx-lambda** — Lambda behind API Gateway: JWKS token validation, DynamoDB association lookup, STS AssumeRole, cluster + association management API
2. **eks-dx-auth-proxy** (simplified) — in-cluster: Kubernetes TokenReview (fast-fail) + forward raw token to Lambda

## What Changes

| Aspect | Current | Target |
|--------|---------|--------|
| Token validation | Kubernetes TokenReview only | Proxy: TokenReview (fast-fail) + Lambda: JWKS validation (authoritative) |
| Association storage | CRD (Fabric8 client) | DynamoDB |
| STS AssumeRole | In-cluster (needs broker IAM role) | Lambda execution role (no IAM on node) |
| Webhook association check | CRD lookup (Fabric8) | Projected SA token → Lambda API |
| CLI | `eks-d-auth-cli` (CRD CRUD) | `eks-dx` (Lambda API via JDK HttpClient) |
| Auth: proxy → Lambda | N/A (local) | Raw pod SA token (audience: `pods.eks.amazonaws.com`) |
| Auth: webhook → Lambda | N/A (local CRD) | Projected SA token (audience: `eks-dx.codriverlabs.ai`) |
| Cluster registration | Not needed | `eks-dx create cluster` (sends JWKS to Lambda) |

## What Stays the Same

- `POST /clusters/{clusterName}/assets` wire format (agent-compatible)
- `AwsCredentialService` (STS AssumeRole + session tags) — moves to Lambda
- `pods.eks.amazonaws.com` audience requirement
- Response JSON format (camelCase, Smithy model)
- EKS Pod Identity Agent (unchanged, just `--endpoint` points to proxy)

## New Module: eks-dx-lambda

### Dependencies

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-amazon-lambda-rest</artifactId>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-amazon-dynamodb</artifactId>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-smallrye-jwt</artifactId>
</dependency>
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>sts</artifactId>
</dependency>
```

### Endpoints

```
# Credential exchange (called by in-cluster proxy)
POST /clusters/{clusterName}/assets
  Auth: raw pod SA token (audience: pods.eks.amazonaws.com)

# Association check (called by webhook)
GET /clusters/{clusterName}/pod-identity-associations
  Auth: webhook SA token (audience: eks-dx.codriverlabs.ai)

# Cluster management (called by eks-dx CLI)
POST   /clusters                                    (register)
GET    /clusters/{name}                             (describe)
GET    /clusters                                    (list)
PUT    /clusters/{name}/jwks                        (refresh JWKS)
DELETE /clusters/{name}                             (deregister)

# Association management (called by eks-dx CLI)
POST   /clusters/{name}/pod-identity-associations              (create)
GET    /clusters/{name}/pod-identity-associations              (list)
GET    /clusters/{name}/pod-identity-associations/{id}         (describe)
DELETE /clusters/{name}/pod-identity-associations/{id}         (delete)
```

### Service Layer

```
ai.codriverlabs.eksdx.lambda/
  resource/
    EksAuthResource.java              # POST /clusters/{name}/assets
    ClusterResource.java              # Cluster CRUD
    AssociationResource.java          # Association CRUD
  service/
    JwksTokenValidationService.java   # JWT validation via JWKS from DynamoDB
    DynamoDbAssociationService.java   # Association lookup
    DynamoDbClusterService.java       # Cluster registration + JWKS storage
    AwsCredentialService.java         # STS AssumeRole (moved from proxy)
  auth/
    TokenAudienceFilter.java          # Validates audience per endpoint
```

### Token Validation Flow

```java
public TokenClaims validateToken(String token, String clusterName) {
    // 1. Read cluster's JWKS from DynamoDB (cached in memory, 5min TTL)
    JsonWebKeySet jwks = clusterService.getJwks(clusterName);

    // 2. Validate JWT: signature, audience, expiry, issuer
    JwtConsumer consumer = new JwtConsumerBuilder()
        .setVerificationKeyResolver(new JwksVerificationKeyResolver(jwks.getJsonWebKeys()))
        .setExpectedAudience("pods.eks.amazonaws.com")
        .setRequireExpirationTime()
        .build();

    JwtClaims claims = consumer.processToClaims(token);

    // 3. Extract claims
    String subject = claims.getSubject(); // system:serviceaccount:<ns>:<sa>
    // ... parse namespace, serviceAccount
}
```

### Audience-Based Auth Filter

```java
@Provider
public class TokenAudienceFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();

        if (path.endsWith("/assets")) {
            // Credential exchange — token is in the request body, validated by EksAuthResource
            return;
        }

        if (path.contains("/pod-identity-associations") && ctx.getMethod().equals("GET")) {
            // Webhook query — validate Bearer token with eks-dx audience
            String token = extractBearer(ctx);
            validateToken(token, "eks-dx.codriverlabs.ai",
                "system:serviceaccount:kube-system:eks-dx-pod-identity-webhook");
            return;
        }

        // Management API (CLI) — no token auth, secured by API Gateway IAM or other mechanism
    }
}
```

## Simplified eks-dx-auth-proxy

### Dependencies (reduced)

```xml
<!-- Keep -->
<dependency>quarkus-rest-jackson</dependency>
<dependency>quarkus-kubernetes-client</dependency>  <!-- TokenReview -->
<dependency>quarkus-smallrye-health</dependency>
<dependency>quarkus-kubernetes</dependency>
<dependency>quarkus-helm</dependency>

<!-- Remove -->
<!-- quarkus-amazon-dynamodb — no DynamoDB -->
<!-- software.amazon.awssdk:sts — no STS -->
<!-- software.amazon.awssdk:eks — already removed -->
<!-- eks-pod-identity-crd — no CRD lookup -->
```

### Simplified Service Layer

```java
@POST
@Path("/{clusterName}/assets")
public Response assumeRoleForPodIdentity(
        @PathParam("clusterName") String clusterName,
        AgentRequest request) {

    // 1. Fast-fail: TokenReview rejects bad tokens before network call
    tokenValidationService.validateToken(request.token, clusterName);

    // 2. Forward raw token to Lambda
    var lambdaRequest = HttpRequest.newBuilder()
        .uri(URI.create(eksDxEndpoint + "/clusters/" + clusterName + "/assets"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + request.token + "\"}"))
        .build();

    var lambdaResponse = httpClient.send(lambdaRequest, HttpResponse.BodyHandlers.ofString());

    // 3. Pass through Lambda response to agent
    return Response.status(lambdaResponse.statusCode())
        .entity(lambdaResponse.body())
        .build();
}
```

### Configuration

```properties
# EKS-DX Lambda endpoint
eks-dx.endpoint=${EKS_DX_ENDPOINT:https://eks-dx.us-east-1.codriverlabs.ai}

# Kubernetes (TokenReview)
quarkus.kubernetes-client.trust-certs=true
quarkus.kubernetes.service-account=eks-dx-auth-proxy

# Helm chart
quarkus.helm.name=eks-dx-auth-proxy
quarkus.helm.description=EKS-DX in-cluster auth proxy (TokenReview + forward)
quarkus.helm.create-tar-file=true
```

## Simplified eks-dx-pod-identity-webhook

### Association Check (Lambda API instead of CRD)

```java
@ApplicationScoped
public class PodIdentityAssociationLookup {

    @ConfigProperty(name = "eks-dx.endpoint")
    String eksDxEndpoint;

    @ConfigProperty(name = "eks.cluster-name")
    String clusterName;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    // Projected SA token mounted at this path
    private static final String TOKEN_PATH = "/var/run/secrets/eks-dx/token";

    public boolean hasAssociation(String clusterName, String namespace, String serviceAccount) {
        String token = Files.readString(Path.of(TOKEN_PATH));

        var request = HttpRequest.newBuilder()
            .uri(URI.create(eksDxEndpoint + "/clusters/" + clusterName
                + "/pod-identity-associations?namespace=" + namespace
                + "&serviceAccount=" + serviceAccount))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 && !parseAssociations(response.body()).isEmpty();
    }
}
```

### Webhook Deployment (projected token volume)

```yaml
spec:
  containers:
  - name: eks-dx-pod-identity-webhook
    volumeMounts:
    - name: eks-dx-token
      mountPath: /var/run/secrets/eks-dx
      readOnly: true
  volumes:
  - name: eks-dx-token
    projected:
      sources:
      - serviceAccountToken:
          audience: eks-dx.codriverlabs.ai
          expirationSeconds: 3600
          path: token
```

## SAM Template

```yaml
AWSTemplateFormatVersion: '2010-09-09'
Transform: AWS::Serverless-2016-10-31

Globals:
  Function:
    Timeout: 30
    MemorySize: 512
    SnapStart:
      ApplyOn: PublishedVersions

Resources:
  EksDxFunction:
    Type: AWS::Serverless::Function
    Properties:
      Handler: io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest
      Runtime: java21
      CodeUri: target/function.zip
      AutoPublishAlias: live
      Policies:
        - DynamoDBCrudPolicy:
            TableName: !Ref ClustersTable
        - DynamoDBCrudPolicy:
            TableName: !Ref AssociationsTable
        - Statement:
            Effect: Allow
            Action: [sts:AssumeRole, sts:TagSession]
            Resource: "arn:aws:iam::*:role/eks-dx-pod-*"
      Events:
        Assets:
          Type: HttpApi
          Properties:
            Path: /clusters/{clusterName}/assets
            Method: POST
        Clusters:
          Type: HttpApi
          Properties:
            Path: /clusters
            Method: ANY
        ClusterByName:
          Type: HttpApi
          Properties:
            Path: /clusters/{name}
            Method: ANY
        ClusterJwks:
          Type: HttpApi
          Properties:
            Path: /clusters/{name}/jwks
            Method: PUT
        Associations:
          Type: HttpApi
          Properties:
            Path: /clusters/{name}/pod-identity-associations
            Method: ANY
        AssociationById:
          Type: HttpApi
          Properties:
            Path: /clusters/{name}/pod-identity-associations/{id}
            Method: ANY

  ClustersTable:
    Type: AWS::DynamoDB::Table
    Properties:
      TableName: eks-dx-clusters
      BillingMode: PAY_PER_REQUEST
      AttributeDefinitions:
        - { AttributeName: clusterName, AttributeType: S }
      KeySchema:
        - { AttributeName: clusterName, KeyType: HASH }

  AssociationsTable:
    Type: AWS::DynamoDB::Table
    Properties:
      TableName: eks-dx-associations
      BillingMode: PAY_PER_REQUEST
      AttributeDefinitions:
        - { AttributeName: PK, AttributeType: S }
        - { AttributeName: SK, AttributeType: S }
      KeySchema:
        - { AttributeName: PK, KeyType: HASH }
        - { AttributeName: SK, KeyType: RANGE }

Outputs:
  Endpoint:
    Value: !Sub "https://${ServerlessHttpApi}.execute-api.${AWS::Region}.amazonaws.com"
```

## Module Structure (Post-Migration)

```
eks-dx-lambda/                    # Lambda service (NEW)
eks-dx-cli/                       # CLI tool (REWRITTEN — Lambda API via JDK HttpClient)
eks-dx-auth-proxy/                   # In-cluster proxy (SIMPLIFIED — TokenReview + forward)
eks-dx-pod-identity-webhook/         # Webhook (MODIFIED — Lambda API for association check)
eks-pod-identity-crd/             # DEPRECATED (kept for offline/disconnected mode)
```

## Migration Steps

1. Create `eks-dx-lambda` module with DynamoDB + JWKS validation + STS
2. Deploy Lambda + API Gateway + DynamoDB tables (SAM)
3. Rewrite `eks-dx-cli` — Fabric8 for JWKS, JDK HttpClient for Lambda API
4. Simplify `eks-dx-auth-proxy` — remove STS/DynamoDB/CRD, add HTTP forward to Lambda
5. Modify `eks-dx-pod-identity-webhook` — projected SA token + Lambda API for association check
6. Test end-to-end: `eks-dx create cluster` → `eks-dx create pod-identity-association` → pod gets credentials
7. Deprecate `eks-pod-identity-crd` module
