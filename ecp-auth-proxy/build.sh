#!/bin/bash

# Build script for AWS EKS Auth Service Proxy

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Default values
BUILD_TYPE="jvm"
PUSH_IMAGE=false
IMAGE_TAG="latest"
REGISTRY=""
MEMORY_LIMIT="10g"
CPU_LIMIT="4"

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --native)
            BUILD_TYPE="native"
            shift
            ;;
        --push)
            PUSH_IMAGE=true
            shift
            ;;
        --tag)
            IMAGE_TAG="$2"
            shift 2
            ;;
        --registry)
            REGISTRY="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo "Options:"
            echo "  --native     Build native executable"
            echo "  --push       Push Docker image to registry"
            echo "  --tag TAG    Docker image tag (default: latest)"
            echo "  --registry   Docker registry prefix"
            echo "  --help       Show this help message"
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            exit 1
            ;;
    esac
done

# Set image name
IMAGE_NAME="ecp-auth-proxy"
if [[ -n "$REGISTRY" ]]; then
    FULL_IMAGE_NAME="$REGISTRY/$IMAGE_NAME:$IMAGE_TAG"
else
    FULL_IMAGE_NAME="$IMAGE_NAME:$IMAGE_TAG"
fi

echo -e "${GREEN}Building AWS EKS Auth Service Proxy${NC}"
echo "Build type: $BUILD_TYPE"
echo "Image name: $FULL_IMAGE_NAME"
echo "Push image: $PUSH_IMAGE"
echo

# Clean previous builds
echo -e "${YELLOW}Cleaning previous builds...${NC}"
./mvnw clean

# Run tests
echo -e "${YELLOW}Running tests...${NC}"
./mvnw test

# Build based on type
if [[ "$BUILD_TYPE" == "native" ]]; then
    echo -e "${YELLOW}Building native executable with Jib...${NC}"
    ./mvnw package -Dnative -Dquarkus.container-image.push="$PUSH_IMAGE" -Dquarkus.container-image.tag="$IMAGE_TAG"
else
    echo -e "${YELLOW}Building JVM executable with Jib...${NC}"
    ./mvnw package -Dquarkus.container-image.push="$PUSH_IMAGE" -Dquarkus.container-image.tag="$IMAGE_TAG"
fi

# Push image if requested
if [[ "$PUSH_IMAGE" == true ]]; then
    echo -e "${YELLOW}Pushing Docker image...${NC}"
    docker push "$FULL_IMAGE_NAME"
fi

echo -e "${GREEN}Build completed successfully!${NC}"
echo "Image: $FULL_IMAGE_NAME"

# Show image size
echo
echo -e "${YELLOW}Image information:${NC}"
docker images "$FULL_IMAGE_NAME" --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}\t{{.CreatedAt}}"
