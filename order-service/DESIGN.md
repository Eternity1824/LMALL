# Order Service - Design Document

## Architecture Overview

The Order Service is the core orchestrator of the e-commerce platform, managing order lifecycle from creation through completion or cancellation. It implements event-driven architecture with transactional outbox pattern for reliability.

## Key Responsibilities

1. **Order Management**: Create, update, and query orders
2. **State Machine**: Manage order state transitions (PENDING → PAID → CONFIRMED/CANCELED)
3. **Event Publishing**: Publish domain events via outbox pattern
4. **Timeout Handling**: Cancel unpaid orders after timeout
5. **Event Consumption**: React to payment and inventory events

## Database Schema

### Orders Table

```sql
CREATE TABLE orders (
    id VARCHAR(64) PRIMARY KEY,           -- Snowflake ID
    user_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,          -- PENDING, PAID, CONFIRMED, CANCELED
    total_amount DECIMAL(10,2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'USD',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    paid_at TIMESTAMP,
    confirmed_at TIMESTAMP,
    canceled_at TIMESTAMP,
    cancel_reason VARCHAR(255),
    version INT DEFAULT 0,                -- Optimistic locking
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
);

CREATE TABLE order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    product_name VARCHAR(255),
    quantity INT NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    subtotal DECIMAL(10,2) NOT NULL,
    FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_order_id (order_id)
);
```

### Outbox and Dedupe Tables

```sql
-- Event publishing outbox
CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(64) NOT NULL,     -- orderId
    event_type VARCHAR(50) NOT NULL,       -- order.created, order.confirmed, etc.
    payload JSONB NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, SENT, FAILED
    created_at TIMESTAMP DEFAULT NOW(),
    sent_at TIMESTAMP,
    retry_count INT DEFAULT 0,
    error_message TEXT,
    INDEX idx_outbox_status (status),
    INDEX idx_outbox_created (created_at)
);

-- Consumer deduplication
CREATE TABLE consumer_dedupe (
    consumer_name VARCHAR(100) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (consumer_name, message_id),
    INDEX idx_processed_at (processed_at)
);

-- Payment timeout tracking
CREATE TABLE order_timeouts (
    order_id VARCHAR(64) PRIMARY KEY,
    timeout_at TIMESTAMP NOT NULL,
    processed BOOLEAN DEFAULT FALSE,
    INDEX idx_timeout_at (timeout_at, processed)
);
```

## Order State Machine

```
    ┌─────────┐
    │ PENDING │──────────────────┐
    └────┬────┘                  │
         │                       │ timeout
         │ payment.captured      │
         ▼                       ▼
    ┌────────┐              ┌──────────┐
    │  PAID  │              │ CANCELED │
    └────┬───┘              └──────────┘
         │
         │ inventory.confirmed
         ▼
    ┌───────────┐
    │ CONFIRMED │
    └───────────┘
```

## API Endpoints

### Create Order

```http
POST /api/orders
Content-Type: application/json

{
  "user_id": "USER123",
  "items": [
    {
      "sku": "ITEM001",
      "quantity": 2,
      "unit_price": 99.99
    }
  ]
}

Response: 200 OK
{
  "order_id": "ORDER-2024-001",
  "status": "PENDING",
  "total_amount": 199.98,
  "payment_deadline": "2024-01-14T10:45:00Z"
}
```

## Order Creation Flow

```java
@Service
@Transactional
public class OrderService {

    public OrderResponse createOrder(CreateOrderRequest request) {
        // 1. Generate order ID
        String orderId = snowflakeIdGenerator.nextId();

        // 2. Validate inventory (optional pre-check)
        validateInventoryAvailability(request.getItems());

        // 3. Calculate totals
        BigDecimal totalAmount = calculateTotal(request.getItems());

        // 4. Create order entity
        Order order = Order.builder()
            .id(orderId)
            .userId(request.getUserId())
            .status(OrderStatus.PENDING)
            .totalAmount(totalAmount)
            .build();

        // 5. Save order and items
        orderRepository.save(order);
        saveOrderItems(orderId, request.getItems());

        // 6. Create timeout record
        LocalDateTime timeoutAt = LocalDateTime.now()
            .plusMinutes(paymentTimeoutMinutes);
        timeoutRepository.save(new OrderTimeout(orderId, timeoutAt));

        // 7. Create outbox event
        OutboxEvent event = OutboxEvent.builder()
            .aggregateId(orderId)
            .eventType("order.created")
            .payload(buildOrderCreatedPayload(order, request.getItems()))
            .build();
        outboxRepository.save(event);

        // 8. Set Redis TTL for timeout (backup mechanism)
        redisTemplate.opsForValue().set(
            "order:timeout:" + orderId,
            orderId,
            paymentTimeoutMinutes,
            TimeUnit.MINUTES
        );

        // Transaction commits here
        // Publisher will pick up the outbox event asynchronously

        return OrderResponse.from(order);
    }
}
```

## Event Handlers

### Payment Captured Handler

```java
@Component
public class PaymentCapturedHandler extends IdempotentMessageHandler {

    @RabbitListener(queues = "order.payment.captured")
    @Transactional
    protected void processMessage(Message message) {
        PaymentCapturedEvent event = parseMessage(message);

        // 1. Find order
        Order order = orderRepository.findById(event.getOrderId())
            .orElseThrow(() -> new OrderNotFoundException(event.getOrderId()));

        // 2. Validate state transition
        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("Order {} not in PENDING state, current: {}",
                order.getId(), order.getStatus());
            return; // Idempotent
        }

        // 3. Update order status
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(LocalDateTime.now());
        orderRepository.save(order);

        // 4. Cancel timeout
        timeoutRepository.deleteById(order.getId());
        redisTemplate.delete("order:timeout:" + order.getId());

        // 5. Publish order confirmed event
        outboxRepository.save(OutboxEvent.builder()
            .aggregateId(order.getId())
            .eventType("order.confirmed")
            .payload(buildOrderConfirmedPayload(order))
            .build());

        log.info("Order {} marked as PAID", order.getId());
    }
}
```

### Inventory Released Handler

```java
@Component
public class InventoryReleasedHandler extends IdempotentMessageHandler {

    @RabbitListener(queues = "order.inventory.released")
    @Transactional
    protected void processMessage(Message message) {
        InventoryReleasedEvent event = parseMessage(message);

        // Update order to CANCELED
        Order order = orderRepository.findById(event.getOrderId())
            .orElseThrow(() -> new OrderNotFoundException(event.getOrderId()));

        if (order.getStatus() == OrderStatus.CANCELED) {
            return; // Already canceled, idempotent
        }

        order.setStatus(OrderStatus.CANCELED);
        order.setCanceledAt(LocalDateTime.now());
        order.setCancelReason("Payment timeout - inventory released");
        orderRepository.save(order);

        // Publish order canceled event
        outboxRepository.save(OutboxEvent.builder()
            .aggregateId(order.getId())
            .eventType("order.canceled")
            .payload(buildOrderCanceledPayload(order))
            .build());

        log.info("Order {} canceled after inventory release", order.getId());
    }
}
```

## Timeout Handling

### Scheduled Timeout Scanner

```java
@Component
public class OrderTimeoutScanner {

    @Scheduled(fixedDelay = 60000) // Every minute
    @Transactional
    public void scanTimeouts() {
        LocalDateTime now = LocalDateTime.now();

        // Find expired timeouts
        List<OrderTimeout> expiredTimeouts = timeoutRepository
            .findByTimeoutAtBeforeAndProcessedFalse(now);

        for (OrderTimeout timeout : expiredTimeouts) {
            try {
                processTimeout(timeout.getOrderId());
                timeout.setProcessed(true);
                timeoutRepository.save(timeout);
            } catch (Exception e) {
                log.error("Failed to process timeout for order {}",
                    timeout.getOrderId(), e);
            }
        }
    }

    private void processTimeout(String orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));

        // Only cancel if still PENDING
        if (order.getStatus() != OrderStatus.PENDING) {
            log.info("Order {} no longer PENDING, skip timeout", orderId);
            return;
        }

        // Create cancellation request event
        outboxRepository.save(OutboxEvent.builder()
            .aggregateId(orderId)
            .eventType("order.cancel.requested")
            .payload(buildCancelRequestPayload(order))
            .build());

        log.info("Timeout cancellation requested for order {}", orderId);
    }
}
```

### Redis Keyspace Notification Handler (Optional)

```java
@Component
public class RedisTimeoutListener {

    @EventListener
    public void handleRedisKeyExpired(RedisKeyExpiredEvent event) {
        String key = event.getKey();

        // Check if it's an order timeout key
        if (key.startsWith("order:timeout:")) {
            String orderId = key.substring("order:timeout:".length());

            // Process timeout
            orderTimeoutService.handleTimeout(orderId);
        }
    }
}
```

## Event Publisher

```java
@Component
public class OrderEventPublisher {

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxRepository
            .findTop100ByStatusOrderByCreatedAt("PENDING");

        for (OutboxEvent event : pending) {
            try {
                // Build message with headers
                Message message = MessageBuilder
                    .withBody(event.getPayload().toString().getBytes())
                    .setMessageId(event.getId().toString())
                    .setHeader("aggregate_id", event.getAggregateId())
                    .setHeader("event_type", event.getEventType())
                    .setHeader("occurred_at", event.getCreatedAt())
                    .build();

                // Publish to RabbitMQ
                rabbitTemplate.send("lmall.events",
                    event.getEventType(), message);

                // Mark as sent
                event.setStatus("SENT");
                event.setSentAt(LocalDateTime.now());

                log.debug("Published event: {} for order: {}",
                    event.getEventType(), event.getAggregateId());

            } catch (Exception e) {
                event.incrementRetryCount();
                if (event.getRetryCount() > MAX_RETRIES) {
                    event.setStatus("FAILED");
                    event.setErrorMessage(e.getMessage());

                    // Alert for manual intervention
                    alertService.sendAlert(AlertLevel.ERROR,
                        "Failed to publish event: " + event.getId());
                }
                log.error("Failed to publish event: {}", event.getId(), e);
            }

            outboxRepository.save(event);
        }
    }
}
```

## Message Queue Configuration

```java
@Configuration
public class OrderMessageConfig {

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange("lmall.events", true, false);
    }

    // Consumer queues
    @Bean
    public Queue paymentCapturedQueue() {
        return QueueBuilder.durable("order.payment.captured")
            .withArgument("x-dead-letter-exchange", "")
            .withArgument("x-dead-letter-routing-key", "order.payment.captured.dlq")
            .withArgument("x-message-ttl", 600000) // 10 minutes
            .build();
    }

    @Bean
    public Queue inventoryReleasedQueue() {
        return QueueBuilder.durable("order.inventory.released")
            .withArgument("x-dead-letter-exchange", "")
            .withArgument("x-dead-letter-routing-key", "order.inventory.released.dlq")
            .build();
    }

    // Bindings
    @Bean
    public Binding paymentCapturedBinding() {
        return BindingBuilder
            .bind(paymentCapturedQueue())
            .to(eventsExchange())
            .with("payment.captured");
    }

    @Bean
    public Binding inventoryReleasedBinding() {
        return BindingBuilder
            .bind(inventoryReleasedQueue())
            .to(eventsExchange())
            .with("inventory.released");
    }

    // DLQ configuration
    @Bean
    public Queue paymentCapturedDLQ() {
        return QueueBuilder.durable("order.payment.captured.dlq")
            .withArgument("x-message-ttl", 604800000) // 7 days
            .build();
    }
}
```

## Error Handling

### Retry Strategy

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        retry:
          enabled: true
          initial-interval: 1000    # 1 second
          multiplier: 2.0           # Exponential backoff
          max-interval: 30000       # 30 seconds max
          max-attempts: 5           # Total 5 attempts
        default-requeue-rejected: false  # Send to DLQ after max attempts
```

### Exception Handling

```java
@ControllerAdvice
public class OrderExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFound(OrderNotFoundException e) {
        return ResponseEntity.status(404)
            .body(ErrorResponse.of("ORDER_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(InvalidOrderStateException e) {
        return ResponseEntity.status(409)
            .body(ErrorResponse.of("INVALID_STATE", e.getMessage()));
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<ErrorResponse> handleConcurrentUpdate(OptimisticLockException e) {
        return ResponseEntity.status(409)
            .body(ErrorResponse.of("CONCURRENT_UPDATE",
                "Order was modified by another request"));
    }
}
```

## Monitoring & Metrics

### Key Metrics

```java
@Component
public class OrderMetrics {

    private final MeterRegistry registry;

    // Order creation metrics
    public void recordOrderCreated(OrderStatus status) {
        registry.counter("orders.created",
            "status", status.name()).increment();
    }

    // State transition metrics
    public void recordStateTransition(OrderStatus from, OrderStatus to) {
        registry.counter("orders.state.transitions",
            "from", from.name(),
            "to", to.name()).increment();
    }

    // Timeout metrics
    public void recordTimeout(String reason) {
        registry.counter("orders.timeouts",
            "reason", reason).increment();
    }

    // Event publishing metrics
    public void recordEventPublished(String eventType, boolean success) {
        registry.counter("orders.events.published",
            "type", eventType,
            "status", success ? "success" : "failure").increment();
    }
}
```

### Health Indicators

```java
@Component
public class OrderServiceHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        try {
            // Check database
            long orderCount = orderRepository.count();

            // Check outbox backlog
            long pendingEvents = outboxRepository.countByStatus("PENDING");

            // Check timeout processing
            long unprocessedTimeouts = timeoutRepository
                .countByTimeoutAtBeforeAndProcessedFalse(LocalDateTime.now());

            if (pendingEvents > 1000) {
                return Health.down()
                    .withDetail("reason", "High outbox backlog")
                    .withDetail("pending_events", pendingEvents)
                    .build();
            }

            if (unprocessedTimeouts > 100) {
                return Health.down()
                    .withDetail("reason", "Timeout processing lag")
                    .withDetail("unprocessed_timeouts", unprocessedTimeouts)
                    .build();
            }

            return Health.up()
                .withDetail("total_orders", orderCount)
                .withDetail("pending_events", pendingEvents)
                .build();

        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
```

## Testing Strategy

### Unit Tests

```java
@Test
public void testOrderCreation() {
    // Given
    CreateOrderRequest request = CreateOrderRequest.builder()
        .userId("USER123")
        .items(Arrays.asList(
            new OrderItem("SKU001", 2, new BigDecimal("99.99"))
        ))
        .build();

    // When
    OrderResponse response = orderService.createOrder(request);

    // Then
    assertNotNull(response.getOrderId());
    assertEquals(OrderStatus.PENDING, response.getStatus());
    assertEquals(new BigDecimal("199.98"), response.getTotalAmount());

    // Verify outbox event created
    verify(outboxRepository).save(argThat(event ->
        event.getEventType().equals("order.created") &&
        event.getAggregateId().equals(response.getOrderId())
    ));
}

@Test
public void testPaymentCapturedHandler() {
    // Given
    Order order = createPendingOrder();
    PaymentCapturedEvent event = new PaymentCapturedEvent(order.getId(), "PAY123");

    // When
    paymentCapturedHandler.handleMessage(createMessage(event));

    // Then
    Order updated = orderRepository.findById(order.getId()).get();
    assertEquals(OrderStatus.PAID, updated.getStatus());
    assertNotNull(updated.getPaidAt());

    // Verify order.confirmed event created
    verify(outboxRepository).save(argThat(e ->
        e.getEventType().equals("order.confirmed")
    ));
}
```

### Integration Tests

```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public class OrderServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.11");

    @Test
    public void testCompleteOrderFlow() {
        // Create order
        OrderResponse order = createOrder();
        assertEquals(OrderStatus.PENDING, order.getStatus());

        // Wait for order.created event
        await().atMost(5, SECONDS).until(() ->
            outboxRepository.findByAggregateIdAndEventType(
                order.getOrderId(), "order.created").isPresent()
        );

        // Simulate payment captured
        publishPaymentCaptured(order.getOrderId());

        // Verify order becomes PAID
        await().atMost(5, SECONDS).until(() ->
            orderRepository.findById(order.getOrderId())
                .map(o -> o.getStatus() == OrderStatus.PAID)
                .orElse(false)
        );

        // Verify order.confirmed event published
        await().atMost(5, SECONDS).until(() ->
            rabbitTemplate.receive("test.order.confirmed") != null
        );
    }
}
```

## Performance Optimizations

1. **Database Indexing**: Proper indexes on frequently queried columns
2. **Batch Processing**: Publisher processes events in batches
3. **Connection Pooling**: Optimized database and RabbitMQ connections
4. **Caching**: Redis cache for frequently accessed orders
5. **Virtual Threads**: Java 21 virtual threads for concurrent processing

## Security Considerations

1. **Input Validation**: Strict validation of order creation requests
2. **Idempotency**: All operations are idempotent via deduplication
3. **Rate Limiting**: API rate limiting to prevent abuse
4. **Audit Logging**: All state changes are logged with user context
5. **Data Encryption**: Sensitive data encrypted at rest

## Future Enhancements

1. **Order Splitting**: Support partial fulfillment
2. **Multi-Currency**: Support for international orders
3. **Recurring Orders**: Subscription-based ordering
4. **Order History**: Event sourcing for complete audit trail
5. **Real-time Updates**: WebSocket notifications for order status changes