# Development Guide

## Prerequisites

- Java 21 or later
- Maven 3.8+
- Docker (for native builds)
- Kubernetes cluster access (optional, for testing)

## Development Workflow

### 1. Local Development

Start in development mode with hot reload:
```bash
./mvnw compile quarkus:dev
```

This starts the application on `http://localhost:8080` with:
- Hot reload enabled
- Dev UI available at `http://localhost:8080/q/dev/`
- Swagger UI at `http://localhost:8080/swagger-ui`

### 2. Testing

Run unit tests:
```bash
./mvnw test
```

Run integration tests:
```bash
./mvnw verify
```

### 3. Building

#### JVM Build
```bash
./mvnw package
```

#### Native Build
```bash
./mvnw package -Dnative
```

#### Native Build with Docker
```bash
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

### 4. Running

#### JVM Mode
```bash
java -jar target/quarkus-app/quarkus-run.jar
```

#### Native Mode
```bash
./target/aws-eks-auth-service-proxy-1.0.0-SNAPSHOT-runner
```

## Configuration

### Development Configuration

Create `src/main/resources/application-dev.properties`:
```properties
# Development overrides
quarkus.log.level=DEBUG
aws.sts.session-duration=PT15M
eks.pod-identity.configmap.name=dev-pod-identity-associations
```

### Testing Configuration

Create `src/test/resources/application-test.properties`:
```properties
# Test overrides
quarkus.log.level=WARN
aws.sts.session-duration=PT5M
```

## Code Structure

```
src/main/java/com/plcloud/eksauth/
├── model/                    # Request/Response models
│   ├── AssumeRoleForPodIdentityRequest.java
│   └── AssumeRoleForPodIdentityResponse.java
├── resource/                 # REST endpoints
│   ├── EksAuthResource.java
│   └── HealthResource.java
├── service/                  # Business logic
│   ├── TokenValidationService.java
│   ├── PodIdentityAssociationService.java
│   └── AwsCredentialService.java
```

## Adding New Features

### 1. Add New Service

```java
@ApplicationScoped
public class MyNewService {
    
    private static final Logger LOG = Logger.getLogger(MyNewService.class);
    
    public void doSomething() {
        LOG.info("Doing something");
    }
}
```

### 2. Add New Endpoint

```java
@Path("/my-endpoint")
@Produces(MediaType.APPLICATION_JSON)
public class MyResource {
    
    @Inject
    MyNewService myService;
    
    @GET
    public Response get() {
        myService.doSomething();
        return Response.ok().build();
    }
}
```

### 3. Add Configuration

In `application.properties`:
```properties
my.new.config.property=default-value
```

In your service:
```java
@ConfigProperty(name = "my.new.config.property")
String myProperty;
```

## Testing Guidelines

### Unit Tests

```java
@QuarkusTest
public class MyServiceTest {
    
    @Inject
    MyService myService;
    
    @Test
    public void testMyService() {
        // Test logic
    }
}
```

### Integration Tests

```java
@QuarkusTest
public class MyResourceTest {
    
    @Test
    public void testEndpoint() {
        given()
          .when().get("/my-endpoint")
          .then()
             .statusCode(200);
    }
}
```

## Debugging

### Enable Debug Logging

```properties
quarkus.log.category."com.plcloud".level=DEBUG
```

### Remote Debugging

JVM mode:
```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 -jar target/quarkus-app/quarkus-run.jar
```

Dev mode (automatic):
```bash
./mvnw compile quarkus:dev
# Debug port 5005 is automatically available
```

## Performance Optimization

### Native Build Optimization

Add to `application.properties`:
```properties
quarkus.native.additional-build-args=--initialize-at-build-time=com.mypackage
```

### Memory Tuning

For native builds:
```properties
quarkus.native.native-image-xmx=4g
```

## Deployment

### Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: eks-auth-proxy
spec:
  replicas: 2
  selector:
    matchLabels:
      app: eks-auth-proxy
  template:
    metadata:
      labels:
        app: eks-auth-proxy
    spec:
      containers:
      - name: eks-auth-proxy
        image: eks-auth-proxy:latest
        ports:
        - containerPort: 8080
        env:
        - name: AWS_ACCOUNT_ID
          value: "123456789012"
        livenessProbe:
          httpGet:
            path: /health/live
            port: 8080
        readinessProbe:
          httpGet:
            path: /health/ready
            port: 8080
```

### Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: eks-auth-proxy
spec:
  selector:
    app: eks-auth-proxy
  ports:
  - port: 80
    targetPort: 8080
  type: ClusterIP
```

## Troubleshooting

### Common Development Issues

1. **Native Build Fails**
   - Check GraalVM compatibility
   - Add reflection configuration if needed
   - Use `--initialize-at-build-time` for problematic classes

2. **Kubernetes Client Issues**
   - Ensure proper RBAC permissions
   - Check cluster connectivity
   - Verify ConfigMap exists

3. **AWS STS Issues**
   - Check AWS credentials
   - Verify IAM role trust policies
   - Check network connectivity to AWS

### Debug Commands

```bash
# Check application health
curl http://localhost:8080/health/live

# View metrics
curl http://localhost:8080/metrics

# Test API endpoint
curl -X POST http://localhost:8080/ \
  -H "Content-Type: application/json" \
  -d '{"ClusterName":"test","Token":"test-token"}'
```
