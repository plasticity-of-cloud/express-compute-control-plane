# Testing Strategy

## Overview

Three layers of testing, each with different scope and infrastructure requirements.

```
Unit Tests          → fast, no containers, mock AWS SDK clients
Integration Tests   → DynamoDB Local + TestContainers, real service logic
End-to-End Tests    → CDK deploy to test account, full request/response cycle
```

---

## Layer 1 — Unit Tests (no containers)

### Credential Service & Mgmt Service

Use Quarkus `@QuarkusTest` with `@InjectMock` to replace AWS SDK clients:

```java
@QuarkusTest
class DynamoDbAssociationServiceTest {

    @InjectMock
    DynamoDbClient dynamoDb;

    @Inject
    DynamoDbAssociationService service;

    @Test
    void lookupAssociation_returnsRoleArn() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder()
                .item(Map.of("roleArn", AttributeValue.fromS("arn:aws:iam::123:role/test")))
                .build());

        String arn = service.lookupRoleArn("cluster1", "default", "my-sa");
        assertThat(arn).isEqualTo("arn:aws:iam::123:role/test");
    }
}
```

### JWT/JWKS Validation

Test `JwksTokenValidationService` with a locally generated RSA key pair — no network calls:

```java
@Test
void validateToken_withValidJwt_succeeds() {
    KeyPair kp = generateRsaKeyPair();
    String jwks = buildJwks(kp.getPublic());
    String token = buildJwt(kp.getPrivate(), "cluster1", "default", "my-sa");

    // Inject JWKS directly into cache, bypassing DynamoDB
    service.injectJwksForTest("cluster1", jwks);
    TokenClaims claims = service.validate(token, "cluster1");

    assertThat(claims.namespace()).isEqualTo("default");
    assertThat(claims.serviceAccount()).isEqualTo("my-sa");
}
```

### Tenant Provisioning — Rollback Logic

Mock all AWS SDK clients and verify rollback calls the right cleanup methods in the right order:

```java
@Test
void provision_whenEipAssociationFails_rollbackReleasesEip() {
    when(ec2.allocateAddress(any())).thenReturn(/* alloc-id=eipalloc-123 */);
    when(ec2.associateAddress(any())).thenThrow(Ec2Exception.builder().message("pending").build());

    assertThrows(Ec2Exception.class, () -> service.provision(...));

    // EIP must be released even though association failed
    verify(ec2).releaseAddress(argThat(r -> r.allocationId().equals("eipalloc-123")));
}
```

---

## Layer 2 — Integration Tests (DynamoDB Local + TestContainers)

### Setup

```java
@QuarkusTest
@TestProfile(DynamoDbLocalProfile.class)
class DynamoDbIntegrationTest {

    @Container
    static GenericContainer<?> dynamoDb = new GenericContainer<>(
        "public.ecr.aws/aws-dynamodb-local/aws-dynamodb-local:latest")
        .withExposedPorts(8000);
}
```

Or use the existing approach: `docker run -p 18000:8000` + `-Dintegration.dynamodb=true`.

### What to test

- Full CRUD lifecycle: create cluster → create association → lookup → delete
- DynamoDB key design: verify `PK=CLUSTER#name / SK=namespace#sa` resolves correctly
- JWKS caching: verify 5-minute TTL, per-`clusterName|audience` isolation
- Tenant state machine: `provisioning → ready → terminated` transitions

---

## Layer 3 — End-to-End Tests (CDK Deploy to Test Account)

### API Gateway Testing

Deploy the CDK stack to a test account and run integration tests against the live API Gateway endpoint:

```bash
./deploy-local.sh --context jvmTenant=true   # fast deploy for testing
ENDPOINT=$(aws cloudformation describe-stacks --stack-name ExpressComputeControlPlaneStack \
  --query 'Stacks[0].Outputs[?OutputKey==`ApiEndpoint`].OutputValue' --output text)
```

```java
@QuarkusIntegrationTest
@TestHTTPEndpoint(value = CredentialExchangeResource.class)
class CredentialServiceE2ETest {
    // Quarkus RestEasy client hits the deployed API Gateway
}
```

**Note**: Management endpoints enforce IAM SigV4 auth. Use test IAM credentials with appropriate permissions.

### Streaming / SSE Testing

Quarkus `@QuarkusTest` supports SSE via the RestEasy Reactive client:

```java
@Test
void streamProgress_emitsEventsUntilReady() {
    Multi<TenantProgress> stream = RestClientBuilder.newBuilder()
        .baseUri(URI.create("http://localhost:8081"))
        .build(TenantStreamClient.class)
        .streamProgress("test-tenant");

    List<TenantProgress> events = stream
        .select().first(5)
        .collect().asList()
        .await().atMost(Duration.ofSeconds(30));

    assertThat(events).last()
        .extracting(TenantProgress::state)
        .isEqualTo("ready");
}
```

For Lambda Function URL streaming specifically, the Quarkus `@QuarkusTest` mode runs the app as a normal HTTP server — SSE works identically. The Lambda-specific `RESPONSE_STREAM` mode is only relevant in the actual Lambda runtime, not in tests.

### API Gateway Request Context Emulation

For unit/integration tests, inject a fake `AwsProxyRequest` directly:

```java
// Build a fake API Gateway proxy request with IAM context
AwsProxyRequest request = new AwsProxyRequest();
request.setRequestContext(buildIamContext("arn:aws:iam::123:user/test-user"));
request.setBody(MAPPER.writeValueAsString(createTenantRequest));

// Invoke the Quarkus JAX-RS resource directly (no HTTP layer)
Response response = given()
    .header("X-Amzn-RequestContext", MAPPER.writeValueAsString(request.getRequestContext()))
    .body(request.getBody())
    .post("/tenants");
```

For full IAM context testing (role resolution, `ecp-role` tag), mock `iam:ListRoleTags` via `@InjectMock` and inject the expected tag value.

---

## Recommended Test Infrastructure

| Component | Tool | Notes |
|-----------|------|-------|
| DynamoDB | `public.ecr.aws/aws-dynamodb-local/aws-dynamodb-local` via TestContainers | Already used |
| Lambda runtime | `@QuarkusTest` (in-process) | Fastest, no Docker |
| API Gateway emulation | CDK deploy to test account | Functional only, no IAM |
| SSE/streaming | Quarkus RestEasy Reactive `Multi<T>` client | Works in `@QuarkusTest` |
| AWS SDK mocking | Mockito `@InjectMock` | For unit tests |
| EC2/IAM/STS | Localstack (optional) | Heavy, use only for rollback/provisioning tests |

---

## Priority Order

1. **Unit tests for rollback logic** — highest value, catches EIP/SG/IAM leaks
2. **Integration tests for DynamoDB key design** — credential exchange hot path
3. **Unit tests for JWT validation** — security-critical
4. **SSE stream tests** — verify terminal event emission and 8-minute timeout
5. **CLI UAT (Robot Framework)** — end-user acceptance, mock + live mode (see `docs/design/testing/cli-uat.md`)
6. **E2E with CDK deploy** — lower priority, covered by layers 1-2
