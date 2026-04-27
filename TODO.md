# EKS-DX TODO

## eks-dx-lambda (Lambda service)

### Service implementations
- [x] `DynamoDbClusterService` — registerCluster, describeCluster, listClusters, updateJwks, deregisterCluster
- [x] `DynamoDbAssociationService` — createAssociation, listAssociations, describeAssociation, deleteAssociation
- [x] `JwksTokenValidationService` — ✅ implemented (jose4j, DynamoDB-backed JWKS cache)
- [x] `AwsCredentialService` — ✅ implemented (STS AssumeRole + session tags)

### Resource implementations
- [x] `ClusterResource` — wire up DynamoDbClusterService, request/response DTOs
- [x] `AssociationResource` — wire up DynamoDbAssociationService, request/response DTOs, generate associationId
- [x] `EksAuthResource` — ✅ implemented (credential exchange endpoint)

### Auth
- [x] `WebhookAuthFilter` — ✅ implemented (SA token audience check)
- [ ] CLI auth — decide mechanism (IAM SigV4 via API Gateway IAM authorizer, or open for now)

### Testing
- [x] Unit tests for JwksTokenValidationService (mock JWKS, valid/invalid/expired tokens)
- [x] Unit tests for DynamoDbAssociationService (mock DynamoDB)
- [x] Unit tests for DynamoDbClusterService (mock DynamoDB)
- [x] Unit tests for AwsCredentialService (mock STS)
- [x] Unit tests for EksAuthResource (mock services, full flow)
- [x] Unit tests for WebhookAuthFilter (path filtering, token validation)
- [x] Unit tests for ClusterResource (HTTP status codes, error handling)
- [x] Unit tests for AssociationResource (HTTP status codes, error handling)
- [ ] Integration test with DynamoDB Local or LocalStack
- [ ] SAM local testing (`sam local start-api`)

### Deployment
- [ ] Validate SAM template (`sam validate`)
- [ ] First deploy to AWS (`sam deploy --guided`)
- [ ] SnapStart verification (cold start benchmarks)

## eks-dx-cli (Native binary CLI)

- [x] `CreateClusterCommand` — parse OIDC issuer from JSON, call apiClient.post()
- [x] `DescribeClusterCommand` — call apiClient.get(), format output
- [x] `ListClustersCommand` — call apiClient.get(), table output
- [x] `UpdateClusterCommand` — refresh JWKS via Fabric8, call apiClient.put()
- [x] `DeleteClusterCommand` — call apiClient.delete()
- [x] `CreateAssociationCommand` — call apiClient.post()
- [x] `ListAssociationsCommand` — call apiClient.get(), table output
- [x] `DescribeAssociationCommand` — call apiClient.get(), format output
- [x] `DeleteAssociationCommand` — call apiClient.delete()
- [ ] `eks-dx configure` command (save endpoint + region to ~/.eks-dx/config)
- [ ] Native binary build verification (GraalVM container build)

## eks-auth-proxy (Simplified in-cluster proxy)

- [x] Remove STS, DynamoDB, CRD dependencies
- [x] Remove EksClientProducer, PodIdentityAssociationService (CRD-based)
- [x] Add JDK HttpClient forward to Lambda endpoint
- [x] Add `EKS_DX_ENDPOINT` env var configuration
- [x] TokenReview stays as fast-fail
- [x] Update Helm chart (quarkus-helm) — remove unused volumes/secrets
- [x] Update tests

## eks-pod-identity-webhook (Modified)

- [x] Replace CRD-based PodIdentityAssociationLookup with Lambda API call
- [x] Add projected SA token volume (audience: eks-dx.plasticity.cloud)
- [x] Add JDK HttpClient for Lambda API
- [x] Remove Fabric8 CRD dependency
- [x] Update Helm chart — add projected token volume, EKS_DX_ENDPOINT env
- [x] Update tests

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
