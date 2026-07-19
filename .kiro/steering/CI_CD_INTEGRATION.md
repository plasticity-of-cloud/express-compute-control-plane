# CI/CD Integration Guide

## Overview

This guide explains how to integrate the AWS EKS Auth Service Proxy into your CI/CD pipelines to enable AWS SDK authentication without requiring actual EKS infrastructure.

## Use Cases

### 1. Testing Applications with AWS SDK
- Test applications that use AWS services without EKS
- Validate IAM role assumptions and permissions
- Integration testing with temporary credentials

### 2. CI/CD Pipeline Authentication
- Provide AWS credentials to build/test processes
- Enable AWS CLI usage in containerized environments
- Support multi-stage builds requiring AWS access

### 3. Development Environment Simulation
- Simulate EKS Workload Identity in local development
- Test role-based access patterns
- Validate application behavior with different IAM roles

## Integration Patterns

### Pattern 1: Sidecar Container

Deploy the proxy as a sidecar container alongside your application:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: app-with-eks-auth
spec:
  containers:
  - name: app
    image: my-app:latest
    env:
    - name: AWS_CONTAINER_CREDENTIALS_FULL_URI
      value: "http://localhost:8080/"
    - name: AWS_CONTAINER_AUTHORIZATION_TOKEN
      value: "Bearer $(cat /var/run/secrets/kubernetes.io/serviceaccount/token)"
  - name: ecp-auth-proxy
    image: ecp-auth-proxy:latest
    ports:
    - containerPort: 8080
    env:
    - name: AWS_ACCOUNT_ID
      value: "123456789012"
    - name: AWS_ACCESS_KEY_ID
      valueFrom:
        secretKeyRef:
          name: aws-credentials
          key: access-key-id
    - name: AWS_SECRET_ACCESS_KEY
      valueFrom:
        secretKeyRef:
          name: aws-credentials
          key: secret-access-key
```

### Pattern 2: Service Deployment

Deploy as a shared service for multiple applications:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ecp-auth-proxy
  namespace: ci-cd
spec:
  replicas: 2
  selector:
    matchLabels:
      app: ecp-auth-proxy
  template:
    metadata:
      labels:
        app: ecp-auth-proxy
    spec:
      serviceAccountName: ecp-auth-proxy
      containers:
      - name: ecp-auth-proxy
        image: ecp-auth-proxy:latest
        ports:
        - containerPort: 8080
        env:
        - name: AWS_ACCOUNT_ID
          value: "123456789012"
        resources:
          requests:
            memory: "64Mi"
            cpu: "50m"
          limits:
            memory: "128Mi"
            cpu: "100m"
---
apiVersion: v1
kind: Service
metadata:
  name: ecp-auth-proxy
  namespace: ci-cd
spec:
  selector:
    app: ecp-auth-proxy
  ports:
  - port: 80
    targetPort: 8080
```

### Pattern 3: Init Container

Use as an init container to set up credentials:

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: aws-job
spec:
  template:
    spec:
      initContainers:
      - name: setup-credentials
        image: ecp-auth-proxy:latest
        command: ["/bin/sh"]
        args:
        - -c
        - |
          # Start proxy in background
          ./application &
          
          # Wait for proxy to be ready
          until curl -f http://localhost:8080/health/ready; do sleep 1; done
          
          # Fetch credentials and save to shared volume
          curl -X POST http://localhost:8080/ \
            -H "Content-Type: application/json" \
            -d "{\"ClusterName\":\"$CLUSTER_NAME\",\"Token\":\"$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)\"}" \
            > /shared/credentials.json
        volumeMounts:
        - name: shared-data
          mountPath: /shared
      containers:
      - name: main
        image: my-app:latest
        volumeMounts:
        - name: shared-data
          mountPath: /shared
      volumes:
      - name: shared-data
        emptyDir: {}
```

## Configuration Examples

### Workload Identity Associations ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: workload-identities
  namespace: kube-system
data:
  # Specific service account mappings
  "ci-cluster:ci-cd:build-sa": "arn:aws:iam::123456789012:role/ci-cd-build-role"
  "ci-cluster:ci-cd:deploy-sa": "arn:aws:iam::123456789012:role/ci-cd-deploy-role"
  "ci-cluster:testing:test-sa": "arn:aws:iam::123456789012:role/testing-role"
  
  # Namespace-level mappings (wildcard)
  "ci-cluster:ci-cd:*": "arn:aws:iam::123456789012:role/ci-cd-default-role"
  "ci-cluster:development:*": "arn:aws:iam::123456789012:role/dev-role"
```

### AWS Credentials Secret

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: aws-credentials
  namespace: ci-cd
type: Opaque
data:
  access-key-id: <base64-encoded-access-key>
  secret-access-key: <base64-encoded-secret-key>
```

### Service Account with RBAC

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: ecp-auth-proxy
  namespace: ci-cd
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: ecp-auth-proxy
rules:
- apiGroups: [""]
  resources: ["configmaps"]
  verbs: ["get", "list"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: ecp-auth-proxy
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: ecp-auth-proxy
subjects:
- kind: ServiceAccount
  name: ecp-auth-proxy
  namespace: ci-cd
```

## Pipeline Integration Examples

### GitHub Actions

```yaml
name: CI/CD with EKS Auth Proxy
on: [push]

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      ecp-auth-proxy:
        image: ecp-auth-proxy:latest
        ports:
          - 8080:8080
        env:
          AWS_ACCOUNT_ID: ${{ secrets.AWS_ACCOUNT_ID }}
          AWS_ACCESS_KEY_ID: ${{ secrets.AWS_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Setup AWS credentials via proxy
      run: |
        export AWS_CONTAINER_CREDENTIALS_FULL_URI=http://localhost:8080/
        export AWS_CONTAINER_AUTHORIZATION_TOKEN="Bearer fake-token"
        
        # Test AWS access
        aws sts get-caller-identity
    
    - name: Run tests
      run: |
        # Your tests here
        ./run-tests.sh
```

### GitLab CI

```yaml
stages:
  - test
  - deploy

variables:
  AWS_CONTAINER_CREDENTIALS_FULL_URI: "http://ecp-auth-proxy:8080/"

test:
  stage: test
  services:
    - name: ecp-auth-proxy:latest
      alias: ecp-auth-proxy
  variables:
    AWS_ACCOUNT_ID: $AWS_ACCOUNT_ID
    AWS_ACCESS_KEY_ID: $AWS_ACCESS_KEY_ID
    AWS_SECRET_ACCESS_KEY: $AWS_SECRET_ACCESS_KEY
  script:
    - export AWS_CONTAINER_AUTHORIZATION_TOKEN="Bearer fake-token"
    - aws sts get-caller-identity
    - ./run-tests.sh
```

### Jenkins Pipeline

```groovy
pipeline {
    agent any
    
    stages {
        stage('Test with EKS Auth Proxy') {
            steps {
                script {
                    docker.image('ecp-auth-proxy:latest').withRun('-p 8080:8080 -e AWS_ACCOUNT_ID=123456789012') { proxy ->
                        // Wait for proxy to be ready
                        sh 'until curl -f http://localhost:8080/health/ready; do sleep 1; done'
                        
                        // Set up AWS credentials
                        withEnv([
                            'AWS_CONTAINER_CREDENTIALS_FULL_URI=http://localhost:8080/',
                            'AWS_CONTAINER_AUTHORIZATION_TOKEN=Bearer fake-token'
                        ]) {
                            sh 'aws sts get-caller-identity'
                            sh './run-tests.sh'
                        }
                    }
                }
            }
        }
    }
}
```

## Docker Compose for Local Development

```yaml
version: '3.8'
services:
  ecp-auth-proxy:
    image: ecp-auth-proxy:latest
    ports:
      - "8080:8080"
    environment:
      - AWS_ACCOUNT_ID=123456789012
      - AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}
      - AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health/ready"]
      interval: 10s
      timeout: 5s
      retries: 3
  
  app:
    image: my-app:latest
    depends_on:
      ecp-auth-proxy:
        condition: service_healthy
    environment:
      - AWS_CONTAINER_CREDENTIALS_FULL_URI=http://ecp-auth-proxy:8080/
      - AWS_CONTAINER_AUTHORIZATION_TOKEN=Bearer fake-token
```

## Monitoring and Observability

### Prometheus Metrics

The proxy exposes metrics at `/metrics`:

```yaml
apiVersion: v1
kind: ServiceMonitor
metadata:
  name: ecp-auth-proxy
spec:
  selector:
    matchLabels:
      app: ecp-auth-proxy
  endpoints:
  - port: http
    path: /metrics
```

### Grafana Dashboard

Key metrics to monitor:
- `eks_auth_requests_total` - Total requests
- `eks_auth_request_duration` - Request duration
- `http_server_requests_seconds` - HTTP metrics
- `jvm_memory_used_bytes` - Memory usage (JVM mode)

### Logging

Configure structured logging:

```properties
quarkus.log.console.json=true
quarkus.log.category."com.plcloud".level=INFO
```

## Security Considerations

### Network Security
- Deploy in trusted networks only
- Use network policies to restrict access
- Consider TLS termination at ingress

### Credential Management
- Use Kubernetes secrets for AWS credentials
- Rotate credentials regularly
- Implement least-privilege IAM policies

### Token Validation
- Service performs basic JWT decoding only
- Suitable for CI/CD environments
- Not recommended for production workloads

## Troubleshooting

### Common Issues

1. **Proxy Not Ready**
   ```bash
   curl http://localhost:8080/health/ready
   ```

2. **AWS Credentials Invalid**
   ```bash
   curl -X POST http://localhost:8080/ \
     -H "Content-Type: application/json" \
     -d '{"ClusterName":"test","Token":"fake-token"}'
   ```

3. **ConfigMap Not Found**
   ```bash
   kubectl get configmap workload-identities -n kube-system
   ```

### Debug Commands

```bash
# Check proxy logs
kubectl logs -l app=ecp-auth-proxy

# Test endpoint directly
kubectl port-forward svc/ecp-auth-proxy 8080:80
curl -X POST http://localhost:8080/ \
  -H "Content-Type: application/json" \
  -d '{"ClusterName":"test","Token":"fake-token"}'

# Verify ConfigMap
kubectl describe configmap workload-identities -n kube-system
```
