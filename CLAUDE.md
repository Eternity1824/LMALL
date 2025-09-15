# CLAUDE.md - LMall Project Memory

## Project Overview
**LMall** - A microservices e-commerce platform implementing distributed transaction patterns
- **Java Version**: 21 (upgraded from 17)
- **Spring Boot**: 3.2.0
- **Architecture**: Microservices on AWS ECS Fargate
- **Communication**: REST, gRPC, AWS SNS/SQS
- **Cloud Provider**: AWS (ECS, Lambda, SNS, SQS, RDS Aurora, ElastiCache)

## Core Design Patterns

### 1. Event-Driven Outbox Pattern (Primary)
**Implementation Strategy:**
- **Transactional Outbox**: Write business data + outbox events in same DB transaction
- **Async Publishing**: Background publisher polls outbox table and sends to MQ
- **Consumer Idempotency**: Dedupe table prevents duplicate processing
- **Message Flow**: Service → Outbox → MQ → Consumer (with idempotency check)

**Key Components:**
- Outbox table: `(id, aggregate_id, event_type, payload, status, created_at)`
- Publisher: Batch read PENDING events, publish to MQ, mark as SENT
- Consumer dedupe: `(consumer_name, message_id, processed_at)`
- MessageId = outbox.id for end-to-end idempotency

### 2. Simplified TCC for Inventory (Secondary)
**Implementation Strategy:**
- **Reserve Phase**: Redis Lua script atomically reserves stock
- **Confirm Phase**: Convert reserved to sold on payment success
- **Cancel Phase**: Release reserved stock on timeout/failure
- **No Local TCC for Payment**: Use external gateway webhooks instead

### 3. AWS Architecture (ADR-007)
**Hot Path (Real-time) - ECS Fargate:**
- Order, Inventory, Payment services on ECS for stable latency
- P99 < 300ms for order processing (excluding payment)
- Auto-scaling based on CPU/RPS/queue depth
- Long-lived connections, no cold starts

**Cold Path (Non-realtime) - Lambda:**
- **CancelScheduler**: EventBridge + Lambda for timeout handling
- **DLQ Replay**: Lambda for failed message recovery
- **Cache Prewarm**: Lambda for Redis initialization
- Acceptable multi-second latency for these operations

**Message Infrastructure - SNS/SQS:**
- **SNS Topics (FIFO)**: `lmall-order-events.fifo`, `lmall-payment-events.fifo`
- **SQS Queues**: FIFO for critical paths, Standard for non-critical
- **MessageGroupId**: orderId for single-order sequencing
- **MessageDeduplicationId**: outbox.id for idempotency
- **DLQ Strategy**: MaxReceiveCount=5, then Lambda replay

**Message Schema:**
```json
{
  "event_type": "order.created",
  "event_id": "outbox.id",
  "occurred_at": "2024-01-14T10:30:00Z",
  "aggregate_id": "orderId",
  "schema_version": "1.0",
  "payload": { /* domain object */ }
}
```

## Module-Specific Implementation Notes

### order-service
- **Event Sourcing**: All state changes via outbox events
- **State Management**: Order state machine (PENDING→PAID→CONFIRMED/CANCELED)
- **Timeout Handling**: Redis TTL keys + scheduled scanner for payment timeout
- **Synchronous Path**: Validate → Write order + outbox → Return 200
- **Snowflake ID**: Distributed unique order ID generation

**Key Features:**
- Transactional outbox for all domain events
- Consumer dedupe table for idempotent event processing
- Redis TTL for payment timeout (no delay queue)
- Virtual threads for handling concurrent orders (Java 21)

### inventory-service
- **Atomic Operations**: Redis Lua scripts for stock management
- **Inventory Structure**: {available, reserved, sold}
- **Event Consumer**: Processes order.created, order.cancel.requested, order.confirmed
- **Outbox Integration**: Write outbox before Redis operations for reliability

**Key Features:**
- Transactional outbox before Redis operations
- Consumer dedupe for event idempotency
- TTL-based reservation cleanup
- Background worker for outbox retry

### mq-service (RabbitMQ Infrastructure)
- **NOT a separate service**: MQ infrastructure shared by all services
- **Event Bus**: Services publish/consume events via outbox pattern
- **Reliability**: DLQ with exponential backoff: 1s→5s→30s→2m→10m→30m
- **Ordering**: Route by orderId for local ordering guarantees

**Infrastructure Features:**
- Topic exchange: `lmall.events`
- Auto-declare queues and bindings on startup
- DLQ replayer for failed message recovery
- Monitoring: queue depth, consumer lag, DLQ rate

### payment-service
- **Webhook Handler**: Receives callbacks from external payment gateways
- **Security**: HMAC-SHA256 signature validation, 5-minute time window
- **Idempotency**: Event ID based deduplication
- **Event Publishing**: Webhook → Validate → Write payment record + outbox → MQ

**Key Features:**
- No local TCC, relies on gateway webhooks
- Webhook idempotency via event_id unique constraint
- Outbox pattern for PaymentCaptured/Failed events
- Payment reconciliation with gateway statements

### user-service
- **Authentication**: JWT tokens with refresh token rotation
- **Profile Management**: User profiles, addresses, preferences
- **Security**: BCrypt password hashing, rate limiting

**TODO Features:**
- OAuth2 integration
- User activity tracking
- GDPR compliance features
- Account merge functionality

### product-service
- **Catalog Management**: Products, categories, pricing
- **Search**: Integration point for Elasticsearch (future)
- **Caching**: Redis for frequently accessed products

**TODO Features:**
- Product recommendation engine
- Dynamic pricing rules
- Inventory integration for real-time availability
- Product review system

### gateway-service
- **Routing**: Path-based routing to microservices
- **Rate Limiting**: Token bucket algorithm, no entry queue
- **Circuit Breaker**: Fail fast on service degradation
- **Special Mode**: Virtual queue only during flash sales (not regular ops)

**Key Features:**
- No default entry queue (removed)
- Rate limiting + circuit breaker for protection
- Flash sale mode with virtual queue (optional)
- Request tracing with correlation IDs

### id-generator
- **Algorithm**: Snowflake for distributed ID generation
- **Structure**: timestamp + machine_id + sequence
- **Performance**: Can generate 4096 IDs per millisecond per node

**TODO Features:**
- ID recycling for deleted entities
- Backup ID generation strategy
- ID obfuscation for public APIs

## Technical Debt & Improvements

1. **Database Migration**: Need Flyway/Liquibase setup for RDS Aurora
2. **Configuration Management**: Use AWS Systems Manager Parameter Store
3. **Service Discovery**: Use AWS Cloud Map with ECS Service Discovery
4. **Monitoring**: CloudWatch + X-Ray for observability
5. **Tracing**: AWS X-Ray for distributed tracing
6. **Testing**:
   - Unit tests with JUnit 5 + Mockito
   - Integration tests with Testcontainers + LocalStack
   - Contract testing with Spring Cloud Contract
7. **Documentation**: OpenAPI/Swagger for API documentation

## Development Guidelines

### Code Style
- Java 21 features encouraged (records, pattern matching, virtual threads)
- Lombok for boilerplate reduction
- MapStruct for DTO mapping
- Immutable objects where possible

### Git Workflow
- Feature branches from main
- Conventional commits (feat:, fix:, docs:, etc.)
- PR required for main branch

### Testing Strategy
- Unit test coverage target: 80%
- Integration tests for critical paths
- Load testing for TCC workflows
- Chaos engineering for resilience testing

## Known Issues & Considerations

1. **Redis Single Point of Failure**: Need Redis Cluster/Sentinel
2. **Message Ordering**: Current design doesn't guarantee strict ordering
3. **Data Consistency**: Eventual consistency between services
4. **Performance**: Need to benchmark TCC overhead
5. **Security**: Need API gateway authentication implementation

## Future Enhancements

1. **GraphQL Gateway**: Aggregate data from multiple services
2. **Event Sourcing**: For order audit trail
3. **CQRS**: Separate read/write models for orders
4. **Multi-tenancy**: Support for multiple sellers
5. **International**: Multi-currency and multi-language support
6. **Analytics**: Real-time sales dashboard
7. **ML Integration**: Fraud detection, recommendation engine

## Commands & Scripts

### Build & Deploy
```bash
# Build all modules
mvn clean install -DskipTests

# Build Docker images
docker build -t lmall/order-service:latest ./order-service

# Deploy to ECS
aws ecs update-service --cluster lmall-cluster --service order-service --force-new-deployment

# Deploy Lambda functions
cd lambda && mvn clean package
sam deploy --stack-name lmall-lambda --s3-bucket deployment-bucket
```

### Local Development
```bash
# Run with LocalStack
docker-compose -f docker-compose.localstack.yml up -d

# Run individual service locally
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run

# Test SNS/SQS locally
aws --endpoint-url=http://localhost:4566 sns publish \
  --topic-arn arn:aws:sns:us-east-1:000000000000:lmall-order-events.fifo \
  --message '{"event_type":"order.created"}'
```

### Monitoring
```bash
# Check ECS service health
aws ecs describe-services --cluster lmall-cluster --services order-service

# View Lambda logs
aws logs tail /aws/lambda/cancel-scheduler --follow

# Check SQS queue depth
aws sqs get-queue-attributes --queue-url https://sqs.us-east-1.amazonaws.com/account/queue.fifo \
  --attribute-names ApproximateNumberOfMessages
```

## Event Flow & Communication

### Order Creation Flow
```
1. User → Gateway → Order Service
2. Order Service → DB (order + outbox[OrderCreated]) → Return 200
3. Publisher → MQ (order.created)
4. Inventory Service consumes → Redis reserve → outbox[InventoryReserved] → MQ
```

### Payment Success Flow
```
1. Payment Gateway → Webhook → Payment Service
2. Payment Service → DB (payment + outbox[PaymentCaptured])
3. Publisher → MQ (payment.captured)
4. Order Service consumes → Update status → outbox[OrderConfirmed] → MQ
5. Inventory Service consumes → Redis (reserved→sold)
```

### Timeout Cancellation Flow
```
1. Redis TTL expires / Scheduled scanner detects timeout
2. Order Service → outbox[OrderCancelRequested] → MQ
3. Inventory Service consumes → Redis release → outbox[InventoryReleased]
4. Order Service consumes → Update status CANCELED
```

## Notes for Claude
- Always check this file before making architectural decisions
- Update this file when implementing new features
- Consider TCC and outbox patterns for all distributed operations
- Prioritize reliability over performance
- Keep eventual consistency in mind