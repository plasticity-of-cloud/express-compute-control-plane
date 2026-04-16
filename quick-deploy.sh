#!/bin/bash
set -e

# Quick deployment script for AWS EKS Auth Service Proxy

echo "🚀 AWS EKS Auth Service Proxy - Quick Deploy"
echo "============================================"

# Check if Docker is running
if ! docker info >/dev/null 2>&1; then
    echo "❌ Docker is not running. Please start Docker first."
    exit 1
fi

# Build the production image
echo "📦 Building production image..."
mvn -pl eks-pod-identity-crd,eks-auth-proxy package -Pproduction -DskipTests \
    -Dquarkus.container-image.build=true \
    -Dquarkus.container-image.push=false \
    -Dquarkus.container-image.tag=latest -q

if [ $? -eq 0 ]; then
    echo "✅ Build successful!"
else
    echo "❌ Build failed!"
    exit 1
fi

# Check if AWS credentials are set
if [ -z "$AWS_ACCESS_KEY_ID" ] || [ -z "$AWS_SECRET_ACCESS_KEY" ]; then
    echo "⚠️  AWS credentials not set. The service will need AWS credentials to function."
    echo "   Set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY environment variables."
fi

# Run the container
echo "🏃 Starting container on port 8080..."
docker run -d \
    --name eks-auth-proxy \
    -p 8080:8080 \
    -e AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-}" \
    -e AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-}" \
    -e AWS_SESSION_TOKEN="${AWS_SESSION_TOKEN:-}" \
    -e AWS_REGION="${AWS_REGION:-us-east-1}" \
    ubuntu/aws-eks-auth-service-proxy:latest

if [ $? -eq 0 ]; then
    echo "✅ Container started successfully!"
    echo ""
    echo "📋 Service Information:"
    echo "   Container: eks-auth-proxy"
    echo "   Port: 8080"
    echo "   Health: http://localhost:8080/health"
    echo "   Metrics: http://localhost:8080/metrics"
    echo "   OpenAPI: http://localhost:8080/openapi"
    echo ""
    echo "🔍 Check logs: docker logs -f eks-auth-proxy"
    echo "🛑 Stop service: docker stop eks-auth-proxy && docker rm eks-auth-proxy"
    echo ""
    echo "⏳ Waiting for service to be ready..."
    
    # Wait for health check
    for i in {1..30}; do
        if curl -s http://localhost:8080/health/ready >/dev/null 2>&1; then
            echo "✅ Service is ready!"
            break
        fi
        echo -n "."
        sleep 1
    done
    
    if [ $i -eq 30 ]; then
        echo "⚠️  Service may not be ready yet. Check logs: docker logs eks-auth-proxy"
    fi
else
    echo "❌ Failed to start container!"
    exit 1
fi
