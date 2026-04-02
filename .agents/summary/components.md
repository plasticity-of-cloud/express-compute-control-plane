# Components

## REST Resources

### EksAuthResource

Main REST endpoint implementing AWS EKS Auth API.

| Property | Value |
|----------|-------|
| Path | `/` |
| Method | POST |
| Produces | application/json |
| Consumes | application/json |

**Endpoint:** `assumeRoleForPodIdentity(Request)`

Processes AssumeRoleForPodIdentity requests by:
1. Validating the service account token
2. Looking up the IAM role association
3. Assuming the role via AWS STS
4. Returning temporary credentials

**Response Codes:**
- 200: Success
- 400: Invalid request
- 403: Access denied
- 500: Internal error

**Metrics:**
- `eks_auth_requests_total` - Total requests
- `eks_auth_request_duration` - Request duration

### HealthResource

Health check endpoints.

| Endpoint | Purpose |
|----------|---------|
| `/health/live` | Liveness probe |
| `/health/ready` | Readiness probe |

**Response:**
```json
{
  "status": "UP",
  "check": "liveness|ready"
}
```

## Services

### TokenValidationService

Validates Kubernetes service account JWT tokens.

**Key Methods:**
- `validateToken(token, clusterName)` - Validates and extracts claims
- `getSubject()` - Returns JWT subject
- `getServiceAccount()` - Returns service account name
- `getNamespace()` - Returns namespace

**Token Claims Extracted:**
- `kubernetes.io/serviceaccount/namespace`
- `kubernetes.io/serviceaccount/service-account.name`
- `sub` (subject)

**Note:** For CI/CD use case, tokens are decoded without cryptographic verification.

### PodIdentityAssociationService

Maps Kubernetes service accounts to AWS IAM roles.

**Key Methods:**
- `getRoleArnForServiceAccount(cluster, namespace, serviceAccount)` - Lookup role ARN
- `generateAssociationId(cluster, namespace, serviceAccount)` - Generate unique ID
- `getDefaultRoleArn(namespace, serviceAccount)` - Generate default role ARN

**Configuration:**
- ConfigMap name: `pod-identity-associations`
- ConfigMap namespace: `kube-system`

**Lookup Priority:**
1. Exact match: `cluster:namespace:serviceaccount`
2. Namespace wildcard: `cluster:namespace:*`
3. Default generated role ARN

### AwsCredentialService

Handles AWS STS operations.

**Key Methods:**
- `assumeRole(roleArn, sessionName)` - Assume IAM role
- `generateSessionName(namespace, serviceAccount)` - Generate session name

**Configuration:**
- Session duration: `aws.sts.session-duration` (default: 1 hour)

## Data Models

### Request Models

#### AssumeRoleForPodIdentityRequest

| Field | Type | JSON Key | Description |
|-------|------|----------|-------------|
| clusterName | String | ClusterName | EKS cluster name |
| token | String | Token | Kubernetes service account JWT |

### Response Models

#### AssumeRoleForPodIdentityResponse

| Field | Type | JSON Key | Description |
|-------|------|----------|-------------|
| credentials | Credentials | Credentials | AWS temporary credentials |
| assumedRoleUser | AssumedRoleUser | AssumedRoleUser | Assumed role information |
| podIdentityAssociation | PodIdentityAssociation | PodIdentityAssociation | Association metadata |
| subject | Subject | Subject | Token subject information |
| audience | String | Audience | Token audience |

#### Nested Models

**Credentials:**
- accessKeyId, secretAccessKey, sessionToken, expiration

**AssumedRoleUser:**
- arn, assumeRoleId

**PodIdentityAssociation:**
- associationId

**Subject:**
- namespace, serviceAccount

## Configuration

### Application Properties

| Property | Default | Description |
|----------|---------|-------------|
| quarkus.http.port | 8080 | HTTP port |
| eks.pod-identity.configmap.name | pod-identity-associations | ConfigMap name |
| eks.pod-identity.configmap.namespace | kube-system | ConfigMap namespace |
| aws.sts.session.duration | PT1H | Session duration |

### Environment Variables

| Variable | Description |
|----------|-------------|
| AWS_ACCOUNT_ID | AWS account ID for default role ARN |
| AWS_ACCESS_KEY_ID | AWS access key |
| AWS_SECRET_ACCESS_KEY | AWS secret key |
| AWS_REGION | AWS region (default: us-east-1) |
