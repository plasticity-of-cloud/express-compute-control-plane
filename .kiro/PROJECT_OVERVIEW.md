# AWS EKS Auth Service Proxy

## Project Overview

This project implements an AWS EKS Auth Service proxy using Quarkus, designed for CI/CD environments where you need to simulate the EKS Pod Identity authentication flow without requiring actual AWS EKS infrastructure.

## Architecture

The service mimics the AWS EKS Auth Service API, specifically the `AssumeRoleForPodIdentity` operation, allowing applications to obtain temporary AWS credentials using Kubernetes service account tokens.

### Key Components

1. **Token Validation Service**: Validates Kubernetes JWT service account tokens
2. **Pod Identity Association Service**: Maps service accounts to IAM roles via ConfigMap
3. **AWS Credential Service**: Uses AWS STS to assume roles and generate temporary credentials
4. **REST API**: Exposes the EKS Auth compatible endpoint

### Technology Stack

- **Java 21**: Latest LTS Java version
- **Quarkus 3.15.1**: Cloud-native Java framework
- **GraalVM Native**: Compiles to native executable for fast startup
- **Fabric8 Kubernetes Client**: Kubernetes API integration
- **AWS SDK v2**: AWS STS integration
- **Micrometer/Prometheus**: Metrics and monitoring

## API Endpoints

### Main Endpoint
- `POST /` - AssumeRoleForPodIdentity operation (compatible with AWS EKS Auth API)

### Health & Monitoring
- `GET /health/live` - Liveness probe
- `GET /health/ready` - Readiness probe  
- `GET /metrics` - Prometheus metrics
- `GET /swagger-ui` - API documentation

## Configuration

### Pod Identity Associations

The service uses a Kubernetes ConfigMap to define pod identity associations:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: pod-identity-associations
  namespace: kube-system
data:
  # Format: cluster:namespace:serviceaccount -> role-arn
  "my-cluster:default:my-service-account": "arn:aws:iam::123456789012:role/my-role"
  "my-cluster:ci-cd:*": "arn:aws:iam::123456789012:role/ci-cd-role"
```

### Environment Variables

- `AWS_ACCOUNT_ID`: AWS account ID for default role ARN generation
- `AWS_REGION`: AWS region for STS operations
- `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`: AWS credentials for STS operations

### Application Properties

Key configuration options in `application.properties`:

- `eks.pod-identity.configmap.name`: ConfigMap name for associations (default: pod-identity-associations)
- `eks.pod-identity.configmap.namespace`: ConfigMap namespace (default: kube-system)
- `aws.sts.session-duration`: STS session duration (default: PT1H)

## Building and Running

### Development Mode
```bash
./mvnw compile quarkus:dev
```

### JVM Mode
```bash
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
```

### Native Mode
```bash
./mvnw package -Dnative
./target/aws-eks-auth-service-proxy-1.0.0-SNAPSHOT-runner
```

### Docker Native
```bash
./mvnw package -Dnative -Dquarkus.native.container-build=true
docker build -f src/main/docker/Dockerfile.native -t eks-auth-proxy .
docker run -i --rm -p 8080:8080 eks-auth-proxy
```

## CI/CD Integration

This service is designed for CI/CD environments where:

1. **No EKS Cluster Required**: Simulates EKS Auth without actual EKS infrastructure
2. **Configurable Associations**: Define role mappings via ConfigMap or environment
3. **Fast Startup**: Native compilation provides sub-second startup times
4. **Kubernetes Native**: Integrates with existing Kubernetes CI/CD pipelines

### Example Usage in CI/CD

```bash
# Start the proxy service
docker run -d -p 8080:8080 \
  -e AWS_ACCOUNT_ID=123456789012 \
  -e AWS_ACCESS_KEY_ID=... \
  -e AWS_SECRET_ACCESS_KEY=... \
  eks-auth-proxy

# Configure your application to use the proxy
export AWS_CONTAINER_CREDENTIALS_FULL_URI=http://localhost:8080/
export AWS_CONTAINER_AUTHORIZATION_TOKEN="Bearer $(cat /var/run/secrets/kubernetes.io/serviceaccount/token)"

# Your application can now use AWS SDK normally
aws s3 ls
```

## Security Considerations

- **Token Validation**: Service account tokens are decoded but not cryptographically verified (suitable for CI/CD)
- **Role Mapping**: Associations are defined in ConfigMap, allowing flexible role assignment
- **AWS Credentials**: Service requires AWS credentials to assume roles via STS
- **Network Security**: Should be deployed in trusted environments only

## Monitoring and Observability

- **Health Checks**: Kubernetes-compatible liveness and readiness probes
- **Metrics**: Prometheus metrics for request counts, durations, and errors
- **Logging**: Structured logging with configurable levels
- **OpenAPI**: Swagger UI for API documentation and testing

## Troubleshooting

### Common Issues

1. **Token Validation Failures**: Check service account token format and claims
2. **Role Association Not Found**: Verify ConfigMap exists and contains correct mappings
3. **STS Assume Role Failures**: Check AWS credentials and IAM role trust policies
4. **Native Build Issues**: Ensure GraalVM compatibility for all dependencies

### Debug Mode

Enable debug logging:
```properties
quarkus.log.category."com.plcloud".level=DEBUG
```

## Limitations

- **Token Verification**: Does not perform cryptographic verification of service account tokens
- **Single Cluster**: Designed for single cluster scenarios (can be extended)
- **Basic Error Handling**: Simplified error responses compared to actual AWS service
- **No Caching**: Each request results in STS call (can be optimized)

## Future Enhancements

- **Token Verification**: Add proper JWT signature verification
- **Caching**: Implement credential caching to reduce STS calls
- **Multi-Cluster**: Support multiple cluster configurations
- **Advanced Logging**: Add request tracing and audit logging
- **Rate Limiting**: Add request rate limiting and throttling
