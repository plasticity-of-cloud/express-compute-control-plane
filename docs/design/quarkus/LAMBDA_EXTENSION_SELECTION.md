# Quarkus Lambda Extension Selection

## Problem

The tenant service is invoked via a **Lambda Function URL** (not API Gateway REST API).
These two invocation types send different JSON payload formats to the Lambda:

| Invocation type | Payload format | Quarkus extension |
|---|---|---|
| API Gateway REST API | `AwsProxyRequest` (v1) | `quarkus-amazon-lambda-rest` |
| API Gateway HTTP API / Function URL | `APIGatewayV2HTTPEvent` (v2) | `quarkus-amazon-lambda-http` |

## Symptom

Using `quarkus-amazon-lambda-rest` with a Function URL causes:

```
MismatchedInputException: Cannot deserialize value of type `java.lang.String`
from Object value (token `JsonToken.START_OBJECT`)
at AwsProxyRequest["requestContext"]["authorizer"]["iam"]
```

The Function URL IAM authorizer sends `iam` as a nested JSON object, but the
`AwsProxyRequest` model (REST extension) expects it as a String via `@JsonAnySetter`.

This worked accidentally in Quarkus ≤ 3.35.4 and broke in 3.36.0 when the
deserialization path changed.

## Fix

Use `quarkus-amazon-lambda-http` for any Lambda invoked via Function URL or HTTP API:

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-amazon-lambda-http</artifactId>  <!-- NOT quarkus-amazon-lambda-rest -->
</dependency>
```

The `quarkus-amazon-lambda-http` extension correctly handles the v2 payload format
including the IAM authorizer context object.

## Rule

- Function URL → `quarkus-amazon-lambda-http`
- API Gateway REST API → `quarkus-amazon-lambda-rest`

The tenant service uses a Function URL (15-min SSE stream requires bypassing API Gateway's
29s timeout), so it must use `quarkus-amazon-lambda-http`.
