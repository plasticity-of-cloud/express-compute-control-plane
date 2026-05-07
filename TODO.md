# EKS-DX TODO

## eks-dx-lambda (Lambda service)

### Service implementations
- [x] `DynamoDbClusterService` — registerCluster, describeCluster, listClusters, updateJwks, deregisterCluster
- [x] `DynamoDbAssociationService` — createAssociation, listAssociations, describeAssociation, deleteAssociation
- [x] `JwksTokenValidationService` — jose4j, DynamoDB-backed JWKS cache
- [x] `AwsCredentialService` — STS AssumeRole + session tags

### Resource implementations
- [x] `ClusterResource` — wire up DynamoDbClusterService, request/response DTOs
- [x] `AssociationResource` — wire up DynamoDbAssociationService, request/response DTOs, generate associationId
- [x] `EksAuthResource` — credential exchange endpoint

### Auth
- [x] `WebhookAuthFilter` — SA token audience check
- [x] CLI auth — IAM SigV4 via API Gateway IAM authorizer

### Testing
- [x] Unit tests for all services and resources (123 tests)
- [x] Integration test with DynamoDB Local (16 tests)
- [x] SAM local testing (`sam validate --lint`)

### Deployment
- [ ] First deploy to AWS (`sam deploy --guided`)
- [ ] SnapStart verification (cold start benchmarks)

## eks-dx-cli (Native binary CLI)

- [x] All 9 commands (cluster + association CRUD)
- [x] `eks-dx configure` command
- [x] IAM SigV4 signing (JDK crypto, no AWS SDK)
- [x] Native binary build config

## eks-dx-auth-proxy (Simplified in-cluster proxy)

- [x] TokenReview fast-fail + Lambda forwarding (18 tests)

## eks-dx-pod-identity-webhook

- [x] Lambda-based association lookup (10 tests)

## Infrastructure

- [x] SAM template (IAM auth, custom domain, CloudWatch alarms, access logging)
- [x] CDK stack (REST API v1, IAM auth, SnapStart, PITR, alarms)

## Documentation

- [x] deploy/README.md — end-to-end setup guide
- [x] AGENTS.md — updated for Lambda architecture
- [x] Architecture diagrams (10 Mermaid diagrams)

## Cleanup

- [x] Removed eks-pod-identity-crd module
- [x] Removed eks-d-auth-cli module
- [x] Updated CI/release workflows
