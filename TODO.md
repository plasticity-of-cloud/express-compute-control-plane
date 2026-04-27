# EKS-DX TODO

## eks-dx-lambda (Lambda service)

### Service implementations
- [ ] `DynamoDbClusterService` — registerCluster, describeCluster, listClusters, updateJwks, deregisterCluster
- [ ] `DynamoDbAssociationService` — createAssociation, listAssociations, describeAssociation, deleteAssociation
- [ ] `JwksTokenValidationService` — ✅ implemented (jose4j, DynamoDB-backed JWKS cache)
- [ ] `AwsCredentialService` — ✅ implemented (STS AssumeRole + session tags)

### Resource implementations
- [ ] `ClusterResource` — wire up DynamoDbClusterService, request/response DTOs
- [ ] `AssociationResource` — wire up DynamoDbAssociationService, request/response DTOs, generate associationId
- [ ] `EksAuthResource` — ✅ implemented (credential exchange endpoint)

### Auth
- [ ] `WebhookAuthFilter` — ✅ implemented (SA token audience check)
- [ ] CLI auth — decide mechanism (IAM SigV4 via API Gateway IAM authorizer, or open for now)

### Testing
- [ ] Unit tests for JwksTokenValidationService (mock JWKS, valid/invalid/expired tokens)
- [ ] Unit tests for DynamoDbAssociationService (mock DynamoDB)
- [ ] Unit tests for DynamoDbClusterService (mock DynamoDB)
- [ ] Integration test with DynamoDB Local or LocalStack
- [ ] SAM local testing (`sam local start-api`)

### Deployment
- [ ] Validate SAM template (`sam validate`)
- [ ] First deploy to AWS (`sam deploy --guided`)
- [ ] SnapStart verification (cold start benchmarks)

## eks-dx-cli (Native binary CLI)

- [ ] `CreateClusterCommand` — parse OIDC issuer from JSON, call apiClient.post()
- [ ] `DescribeClusterCommand` — call apiClient.get(), format output
- [ ] `ListClustersCommand` — call apiClient.get(), table output
- [ ] `UpdateClusterCommand` — refresh JWKS via Fabric8, call apiClient.put()
- [ ] `DeleteClusterCommand` — call apiClient.delete()
- [ ] `CreateAssociationCommand` — call apiClient.post()
- [ ] `ListAssociationsCommand` — call apiClient.get(), table output
- [ ] `DescribeAssociationCommand` — call apiClient.get(), format output
- [ ] `DeleteAssociationCommand` — call apiClient.delete()
- [ ] `eks-dx configure` command (save endpoint + region to ~/.eks-dx/config)
- [ ] Native binary build verification (GraalVM container build)

## eks-auth-proxy (Simplified in-cluster proxy)

- [ ] Remove STS, DynamoDB, CRD dependencies
- [ ] Remove EksClientProducer, PodIdentityAssociationService (CRD-based)
- [ ] Add JDK HttpClient forward to Lambda endpoint
- [ ] Add `EKS_DX_ENDPOINT` env var configuration
- [ ] TokenReview stays as fast-fail
- [ ] Update Helm chart (quarkus-helm) — remove unused volumes/secrets
- [ ] Update tests

## eks-pod-identity-webhook (Modified)

- [ ] Replace CRD-based PodIdentityAssociationLookup with Lambda API call
- [ ] Add projected SA token volume (audience: eks-dx.plasticity.cloud)
- [ ] Add JDK HttpClient for Lambda API
- [ ] Remove Fabric8 CRD dependency
- [ ] Update Helm chart — add projected token volume, EKS_DX_ENDPOINT env
- [ ] Update tests

## Infrastructure

- [ ] CDK alternative to SAM template (optional)
- [ ] Custom domain for API Gateway (eks-dx.plasticity.cloud)
- [ ] CloudWatch alarms (Lambda errors, DynamoDB throttling)
- [ ] API Gateway access logging

## Documentation

- [ ] Update deploy/README.md for Lambda architecture
- [ ] Update AGENTS.md with new module structure
- [ ] End-to-end setup guide (deploy Lambda → register cluster → create association → test pod)
- [ ] Architecture diagram (final version)

## Deprecation

- [ ] Mark eks-pod-identity-crd as deprecated in pom.xml
- [ ] Mark eks-d-auth-cli as deprecated in pom.xml
- [ ] Update CI/release workflows for new module structure
- [ ] Remove old modules after migration is validated
