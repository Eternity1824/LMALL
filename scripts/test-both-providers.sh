#!/bin/bash
# Test script for both RabbitMQ and LocalStack message providers

set -e

echo "==============================================="
echo "LMall Dual Provider Testing"
echo "==============================================="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${GREEN}[✓]${NC} $1"
}

print_error() {
    echo -e "${RED}[✗]${NC} $1"
}

print_info() {
    echo -e "${YELLOW}[i]${NC} $1"
}

# Function to wait for service
wait_for_service() {
    local service=$1
    local port=$2
    local max_attempts=30
    local attempt=0

    print_info "Waiting for $service on port $port..."

    while ! nc -z localhost $port 2>/dev/null; do
        attempt=$((attempt + 1))
        if [ $attempt -ge $max_attempts ]; then
            print_error "$service failed to start on port $port"
            return 1
        fi
        sleep 2
    done

    print_status "$service is ready on port $port"
}

# Function to run tests with a specific profile
run_tests() {
    local profile=$1
    local provider=$2

    echo ""
    echo "-----------------------------------------------"
    echo "Testing with $provider (Profile: $profile)"
    echo "-----------------------------------------------"

    export SPRING_PROFILES_ACTIVE=$profile

    # Run unit tests
    print_info "Running unit tests..."
    mvn test -q -Dspring.profiles.active=$profile

    if [ $? -eq 0 ]; then
        print_status "Unit tests passed"
    else
        print_error "Unit tests failed"
        return 1
    fi

    # Run integration tests if they exist
    if [ -d "src/test/java/integration" ] || grep -q "integration-test" pom.xml; then
        print_info "Running integration tests..."
        mvn verify -Pintegration-test -q -Dspring.profiles.active=$profile

        if [ $? -eq 0 ]; then
            print_status "Integration tests passed"
        else
            print_error "Integration tests failed"
            return 1
        fi
    fi

    return 0
}

# Main script
main() {
    echo "Starting infrastructure services..."

    # Start common services
    docker-compose up -d postgres redis
    wait_for_service "PostgreSQL" 5432
    wait_for_service "Redis" 6379

    # Test with RabbitMQ
    print_info "Starting RabbitMQ..."
    docker-compose up -d rabbitmq
    wait_for_service "RabbitMQ" 5672

    # Wait for RabbitMQ management to be ready
    sleep 5

    if run_tests "local-rabbitmq" "RabbitMQ"; then
        print_status "RabbitMQ tests completed successfully"
        RABBITMQ_RESULT="PASSED"
    else
        print_error "RabbitMQ tests failed"
        RABBITMQ_RESULT="FAILED"
    fi

    # Stop RabbitMQ
    docker-compose stop rabbitmq

    # Test with LocalStack
    print_info "Starting LocalStack..."
    docker-compose --profile aws up -d localstack
    wait_for_service "LocalStack" 4566

    # Wait for LocalStack initialization
    print_info "Waiting for LocalStack initialization..."
    sleep 10

    # Check if LocalStack is initialized
    if docker logs lmall-localstack 2>&1 | grep -q "LocalStack initialization complete"; then
        print_status "LocalStack initialized"
    else
        print_info "Running LocalStack initialization manually..."
        docker exec lmall-localstack /etc/localstack/init/ready.d/init.sh
    fi

    if run_tests "local-aws" "LocalStack (AWS)"; then
        print_status "LocalStack tests completed successfully"
        LOCALSTACK_RESULT="PASSED"
    else
        print_error "LocalStack tests failed"
        LOCALSTACK_RESULT="FAILED"
    fi

    # Summary
    echo ""
    echo "==============================================="
    echo "Test Results Summary"
    echo "==============================================="
    echo "RabbitMQ:   $RABBITMQ_RESULT"
    echo "LocalStack: $LOCALSTACK_RESULT"
    echo "==============================================="

    # Cleanup
    if [ "$1" != "--keep-running" ]; then
        print_info "Cleaning up services..."
        docker-compose down
        docker-compose --profile aws down
    else
        print_info "Services kept running (use 'docker-compose down' to stop)"
    fi

    # Exit with appropriate code
    if [ "$RABBITMQ_RESULT" = "PASSED" ] && [ "$LOCALSTACK_RESULT" = "PASSED" ]; then
        exit 0
    else
        exit 1
    fi
}

# Check if we're in the project root
if [ ! -f "docker-compose.yml" ]; then
    print_error "Please run this script from the project root directory"
    exit 1
fi

# Check dependencies
command -v docker >/dev/null 2>&1 || { print_error "Docker is required but not installed."; exit 1; }
command -v docker-compose >/dev/null 2>&1 || { print_error "Docker Compose is required but not installed."; exit 1; }
command -v mvn >/dev/null 2>&1 || { print_error "Maven is required but not installed."; exit 1; }
command -v nc >/dev/null 2>&1 || { print_error "netcat (nc) is required but not installed."; exit 1; }

# Run main function with all arguments
main "$@"