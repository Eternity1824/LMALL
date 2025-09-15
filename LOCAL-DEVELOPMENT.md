# Local Development Guide

This guide provides instructions for running LMall locally with two messaging options:
1. **RabbitMQ** (Default) - Traditional message broker, simpler setup
2. **AWS LocalStack** - Simulates AWS services (SNS, SQS, Lambda)

## Prerequisites

- Docker & Docker Compose (v2.0+)
- Java 21
- Maven 3.8+
- Node.js 18+ (for frontend, if applicable)
- AWS CLI (for LocalStack testing)

## Quick Start

### Option 1: RabbitMQ (Recommended for Quick Start)

```bash
# 1. Start infrastructure with RabbitMQ
docker-compose up -d postgres redis rabbitmq

# 2. Wait for services to be healthy
docker-compose ps

# 3. Run services with RabbitMQ profile
SPRING_PROFILES_ACTIVE=local-rabbitmq mvn spring-boot:run

# Or run all services in containers
docker-compose up -d
```

### Option 2: AWS LocalStack

```bash
# 1. Start infrastructure with LocalStack
docker-compose --profile aws up -d postgres redis localstack

# 2. Wait for LocalStack initialization
docker logs -f lmall-localstack
# Wait for "LocalStack initialization complete!"

# 3. Run services with AWS profile
SPRING_PROFILES_ACTIVE=local-aws mvn spring-boot:run

# Or run all services with AWS profile
SPRING_PROFILES_ACTIVE=local-aws docker-compose --profile aws up -d
```

## Detailed Setup

### 1. Database Setup

The PostgreSQL database will be automatically initialized with the script at `scripts/init-db.sql`.

```bash
# Check database is ready
docker exec lmall-postgres psql -U postgres -d lmall -c "\dt"

# Manual connection
docker exec -it lmall-postgres psql -U postgres -d lmall
```

### 2. RabbitMQ Setup

RabbitMQ comes pre-configured with exchanges, queues, and bindings.

```bash
# Access RabbitMQ Management UI
open http://localhost:15672
# Login: admin / admin

# Verify queues are created
docker exec lmall-rabbitmq rabbitmqctl list_queues

# Send test message
docker exec lmall-rabbitmq rabbitmqadmin publish \
    exchange=lmall.events \
    routing_key=order.created \
    payload='{"orderId":"TEST-001","userId":"USER-123"}'
```

### 3. LocalStack Setup

LocalStack simulates AWS services locally.

```bash
# Configure AWS CLI for LocalStack
aws configure set aws_access_key_id test
aws configure set aws_secret_access_key test
aws configure set region us-east-1

# Alias for LocalStack
alias awslocal='aws --endpoint-url=http://localhost:4566'

# List created resources
awslocal sns list-topics
awslocal sqs list-queues
awslocal dynamodb list-tables

# Send test message to SNS
awslocal sns publish \
    --topic-arn arn:aws:sns:us-east-1:000000000000:lmall-order-events.fifo \
    --message '{"orderId":"TEST-001"}' \
    --message-group-id "TEST-001" \
    --message-attributes '{"event_type":{"DataType":"String","StringValue":"order.created"}}'

# Check SQS queue messages
awslocal sqs receive-message \
    --queue-url http://localhost:4566/000000000000/inventory-order-created.fifo
```

## Running Services

### Individual Service Startup

```bash
# Order Service
cd order-service
SPRING_PROFILES_ACTIVE=local-rabbitmq mvn spring-boot:run
# Or for AWS
SPRING_PROFILES_ACTIVE=local-aws mvn spring-boot:run

# Inventory Service
cd inventory-service
SPRING_PROFILES_ACTIVE=local-rabbitmq mvn spring-boot:run

# Payment Service
cd payment-service
SPRING_PROFILES_ACTIVE=local-rabbitmq mvn spring-boot:run

# Gateway Service
cd gateway-service
mvn spring-boot:run
```

### Docker Compose Commands

```bash
# Start all services with RabbitMQ (default)
docker-compose up -d

# Start all services with LocalStack
docker-compose --profile aws up -d

# Start with monitoring tools
docker-compose --profile monitoring up -d

# View logs
docker-compose logs -f order-service

# Stop all services
docker-compose down

# Clean everything (including volumes)
docker-compose down -v
```

## Testing

### API Testing

```bash
# Health check
curl http://localhost:8080/actuator/health

# Create order (via Gateway)
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "USER-123",
    "items": [
      {
        "sku": "ITEM-001",
        "quantity": 2,
        "unitPrice": 99.99
      }
    ]
  }'

# Get order status
curl http://localhost:8080/api/orders/ORDER-ID

# Simulate payment webhook
curl -X POST http://localhost:8084/webhooks/payment/stripe \
  -H "Content-Type: application/json" \
  -H "X-Signature: test-signature" \
  -H "X-Timestamp: $(date +%s)" \
  -d '{
    "eventId": "evt_123",
    "orderId": "ORDER-ID",
    "status": "captured",
    "amount": 199.98
  }'
```

### Message Queue Testing

#### RabbitMQ Testing

```bash
# Monitor queues
watch -n 2 'docker exec lmall-rabbitmq rabbitmqctl list_queues name messages consumers'

# View message rates
docker exec lmall-rabbitmq rabbitmqctl list_queues name message_stats.publish_details.rate

# Purge queue (for testing)
docker exec lmall-rabbitmq rabbitmqctl purge_queue inventory.order.created
```

#### LocalStack Testing

```bash
# Monitor SQS queues
watch -n 2 'awslocal sqs get-queue-attributes \
    --queue-url http://localhost:4566/000000000000/inventory-order-created.fifo \
    --attribute-names ApproximateNumberOfMessages'

# View DLQ messages
awslocal sqs receive-message \
    --queue-url http://localhost:4566/000000000000/inventory-order-created-dlq.fifo \
    --max-number-of-messages 10

# Trigger Lambda manually (if deployed)
awslocal lambda invoke \
    --function-name lmall-cancel-scheduler-local \
    --payload '{}' \
    response.json
```

## Switching Between RabbitMQ and LocalStack

### Environment Variables

```bash
# Use RabbitMQ
export SPRING_PROFILES_ACTIVE=local-rabbitmq

# Use LocalStack
export SPRING_PROFILES_ACTIVE=local-aws

# Or set in .env file
echo "SPRING_PROFILES_ACTIVE=local-rabbitmq" > .env
```

### Application Properties

Services automatically load the correct configuration based on the active profile:
- `config/application-local-rabbitmq.yml` - RabbitMQ configuration
- `config/application-local-aws.yml` - LocalStack configuration

### IDE Configuration

#### IntelliJ IDEA

1. Run → Edit Configurations
2. Add Environment Variable: `SPRING_PROFILES_ACTIVE=local-rabbitmq`
3. Or add VM Option: `-Dspring.profiles.active=local-rabbitmq`

#### VS Code

Add to `.vscode/launch.json`:

```json
{
  "configurations": [
    {
      "type": "java",
      "name": "Order Service (RabbitMQ)",
      "env": {
        "SPRING_PROFILES_ACTIVE": "local-rabbitmq"
      }
    },
    {
      "type": "java",
      "name": "Order Service (AWS)",
      "env": {
        "SPRING_PROFILES_ACTIVE": "local-aws"
      }
    }
  ]
}
```

## Monitoring

### Access Monitoring Tools

```bash
# Start monitoring stack
docker-compose --profile monitoring up -d

# Access tools
open http://localhost:15672  # RabbitMQ Management
open http://localhost:3000   # Grafana (admin/admin)
open http://localhost:9090   # Prometheus
```

### View Application Metrics

```bash
# Prometheus metrics
curl http://localhost:8081/actuator/prometheus

# Health check
curl http://localhost:8081/actuator/health

# Application info
curl http://localhost:8081/actuator/info
```

## Troubleshooting

### Common Issues

#### 1. Port Already in Use

```bash
# Find process using port
lsof -i :5432  # PostgreSQL
lsof -i :6379  # Redis
lsof -i :5672  # RabbitMQ
lsof -i :4566  # LocalStack

# Kill process
kill -9 <PID>
```

#### 2. Docker Resources

```bash
# Clean up Docker resources
docker system prune -a --volumes

# Increase Docker memory (Docker Desktop)
# Preferences → Resources → Memory: 8GB minimum
```

#### 3. LocalStack Not Initializing

```bash
# Check LocalStack logs
docker logs lmall-localstack

# Manually run initialization
docker exec lmall-localstack /etc/localstack/init/ready.d/init.sh

# Reset LocalStack
docker-compose --profile aws down
docker volume rm lmall_localstack-data
docker-compose --profile aws up -d
```

#### 4. RabbitMQ Connection Issues

```bash
# Check RabbitMQ status
docker exec lmall-rabbitmq rabbitmqctl status

# Reset RabbitMQ
docker exec lmall-rabbitmq rabbitmqctl reset

# Reload definitions
docker exec lmall-rabbitmq rabbitmqctl import_definitions /etc/rabbitmq/definitions.json
```

#### 5. Database Connection Issues

```bash
# Check PostgreSQL logs
docker logs lmall-postgres

# Reset database
docker-compose down -v
docker-compose up -d postgres
docker exec lmall-postgres psql -U postgres -f /docker-entrypoint-initdb.d/init.sql
```

## Performance Testing

### Load Testing with K6

```javascript
// k6-test.js
import http from 'k6/http';
import { check } from 'k6';

export let options = {
  stages: [
    { duration: '1m', target: 10 },
    { duration: '3m', target: 100 },
    { duration: '1m', target: 0 },
  ],
};

export default function () {
  let payload = JSON.stringify({
    userId: `USER-${Math.random()}`,
    items: [
      {
        sku: 'ITEM-001',
        quantity: Math.floor(Math.random() * 5) + 1,
        unitPrice: 99.99
      }
    ]
  });

  let params = {
    headers: { 'Content-Type': 'application/json' },
  };

  let response = http.post('http://localhost:8080/api/orders', payload, params);

  check(response, {
    'status is 200': (r) => r.status === 200,
    'response time < 500ms': (r) => r.timings.duration < 500,
  });
}
```

Run load test:

```bash
k6 run k6-test.js
```

## Development Workflow

### 1. Feature Development

```bash
# Create feature branch
git checkout -b feature/new-feature

# Start services
docker-compose up -d

# Make changes and test
mvn test
mvn spring-boot:run

# Run integration tests
mvn verify -Pintegration-test
```

### 2. Debugging

```bash
# Enable debug logging
export LOGGING_LEVEL_COM_LMALL=DEBUG

# Or in application.yml
logging:
  level:
    com.lmall: DEBUG
    org.springframework.amqp: DEBUG  # For RabbitMQ
    software.amazon.awssdk: DEBUG    # For AWS

# Remote debugging
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar target/app.jar
```

### 3. Testing Message Flow

```bash
# Terminal 1: Watch logs
docker-compose logs -f order-service inventory-service

# Terminal 2: Send test order
./scripts/test-order-flow.sh

# Terminal 3: Monitor queues (RabbitMQ)
watch 'docker exec lmall-rabbitmq rabbitmqctl list_queues'

# Or monitor SQS (LocalStack)
watch 'awslocal sqs get-queue-attributes \
    --queue-url http://localhost:4566/000000000000/inventory-order-created.fifo \
    --attribute-names All | jq'
```

## CI/CD Integration

### GitHub Actions

```yaml
name: Local Test
on: push

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2

      - name: Start services
        run: docker-compose up -d

      - name: Wait for services
        run: |
          timeout 60 bash -c 'until docker exec lmall-postgres pg_isready; do sleep 1; done'
          timeout 60 bash -c 'until docker exec lmall-rabbitmq rabbitmqctl status; do sleep 1; done'

      - name: Run tests
        run: mvn test

      - name: Integration tests
        run: mvn verify -Pintegration-test

      - name: Stop services
        run: docker-compose down -v
```

## Additional Resources

- [RabbitMQ Management UI Guide](https://www.rabbitmq.com/management.html)
- [LocalStack Documentation](https://docs.localstack.cloud/)
- [Spring Cloud AWS Reference](https://docs.awspring.io/spring-cloud-aws/docs/current/reference/html/)
- [Docker Compose Documentation](https://docs.docker.com/compose/)

## Support

For issues or questions:
1. Check the [Troubleshooting](#troubleshooting) section
2. Review logs: `docker-compose logs <service-name>`
3. Check service health: `curl http://localhost:808X/actuator/health`
4. Create an issue in the repository with:
   - Error messages
   - Steps to reproduce
   - Environment details (OS, Docker version, etc.)