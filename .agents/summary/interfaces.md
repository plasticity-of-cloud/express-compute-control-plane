# Interfaces and APIs

## REST API

### AssumeRoleForPodIdentity

Assumes an IAM role for a Kubernetes service account.

**Endpoint:** `POST /`

**Request:**
```json
{
  "ClusterName": "string",
  "Token": "string"
}
```

**Request Schema:**
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| ClusterName | string | Yes | EKS cluster name |
| Token | string | Yes | Kubernetes service account JWT |

**Response (200):**
```json
{
  "Credentials": {
    "AccessKeyId": "string",
    "SecretAccessKey": "string",
    "SessionToken": "string",
    "Expiration": "2024-01-01T00:00:00Z"
  },
  "AssumedRoleUser": {
    "Arn": "string",
    "AssumeRoleId": "string"
  },
  "PodIdentityAssociation": {
    "AssociationId": "string"
  },
  "Subject": {
    "Namespace": "string",
    "ServiceAccount": "string"
  },
  "Audience": "string"
}
```

**Error Responses:**

400 Bad Request:
```json
{
  "error": "InvalidRequestException",
  "message": "error message"
}
```

403 Forbidden:
```json
{
  "error": "AccessDeniedException",
  "message": "error message"
}
```

500 Internal Server Error:
```json
{
  "error": "InternalServerException",
  "message": "Internal server error"
}
```

## Kubernetes Integration

### ConfigMap Schema

The pod identity associations are stored in a ConfigMap.

**ConfigMap:** `pod-identity-associations` in `kube-system` namespace

**Data Format:**
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: pod-identity-associations
  namespace: kube-system
data:
  "cluster-name:namespace:serviceaccount": "arn:aws:iam::123456789012:role/role-name"
  "cluster-name:namespace:*": "arn:aws:iam::123456789012:role/namespace-role"
```

**Key Format:** `{cluster}:{namespace}:{serviceaccount}`

**Wildcard Support:** Use `*` for service account to match all service accounts in a namespace.

## AWS STS Integration

### AssumeRole Request

The service calls AWS STS `AssumeRole` with:

```java
AssumeRoleRequest.builder()
    .roleArn(roleArn)
    .roleSessionName(sessionName)
    .durationSeconds(sessionDuration)
    .build()
```

### Response Format

Returns standard AWS STS credentials:
- AccessKeyId
- SecretAccessKey
- SessionToken
- Expiration

## Health Check API

### Liveness Probe

**Endpoint:** `GET /health/live`

**Response:**
```json
{
  "status": "UP",
  "check": "liveness"
}
```

### Readiness Probe

**Endpoint:** `GET /health/ready`

**Response:**
```json
{
  "status": "UP",
  "check": "readiness"
}
```

## Metrics API

**Endpoint:** `GET /metrics`

Prometheus-formatted metrics including:
- `eks_auth_requests_total`
- `eks_auth_request_duration`
- Quarkus and Micrometer system metrics

## OpenAPI Documentation

**Endpoint:** `GET /openapi`

**UI:** `GET /swagger-ui`

Full OpenAPI 3.0 specification for the API.
