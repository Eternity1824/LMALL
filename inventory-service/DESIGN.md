# Inventory Service - Redis Design Document

## Redis Data Structure Design

### 1. Inventory Storage Structure

```redis
# Main inventory hash
inventory:{itemId} = {
    "available": 1000,    # Stock ready to sell
    "reserved": 50,       # Reserved by TCC Try phase
    "sold": 200          # Already sold and confirmed
}

# Example:
inventory:ITEM123 = {
    "available": 1000,
    "reserved": 50,
    "sold": 200
}
```

### 2. TCC Reservation Tracking

```redis
# Track which order reserved what quantity
reservation:{orderId} = {
    "itemId": "ITEM123",
    "quantity": 5,
    "timestamp": "2024-01-14T10:30:00",
    "ttl": 300  # 5 minutes timeout
}

# Set with expiration for automatic cleanup
SETEX reservation:ORDER456 300 "{...}"
```

### 3. Hot Product Cache

```redis
# Pre-warm frequently accessed products
hot:inventory:{itemId} = {
    "available": 1000,
    "lastUpdate": "2024-01-14T10:30:00"
}

# TTL: 60 seconds for hot products
```

## Lua Scripts for Atomic Operations

### 1. Reserve Stock (TCC Try Phase)

```lua
-- reserve_stock.lua
-- KEYS[1] = inventory:{itemId}
-- ARGV[1] = quantity to reserve

local inventory_key = KEYS[1]
local quantity = tonumber(ARGV[1])

-- Get current stock
local available = redis.call('HGET', inventory_key, 'available')
if not available then
    return {0, "ITEM_NOT_FOUND"}
end

available = tonumber(available)

-- Check if enough stock
if available < quantity then
    return {0, "INSUFFICIENT_STOCK", available}
end

-- Atomically move from available to reserved
redis.call('HINCRBY', inventory_key, 'available', -quantity)
redis.call('HINCRBY', inventory_key, 'reserved', quantity)

return {1, "SUCCESS", available - quantity}
```

### 2. Confirm Stock (TCC Confirm Phase)

```lua
-- confirm_stock.lua
-- KEYS[1] = inventory:{itemId}
-- KEYS[2] = reservation:{orderId}
-- ARGV[1] = quantity

local inventory_key = KEYS[1]
local reservation_key = KEYS[2]
local quantity = tonumber(ARGV[1])

-- Verify reservation exists
local reservation = redis.call('GET', reservation_key)
if not reservation then
    return {0, "RESERVATION_NOT_FOUND"}
end

-- Move from reserved to sold
local reserved = tonumber(redis.call('HGET', inventory_key, 'reserved'))
if reserved < quantity then
    return {0, "INVALID_RESERVATION"}
end

redis.call('HINCRBY', inventory_key, 'reserved', -quantity)
redis.call('HINCRBY', inventory_key, 'sold', quantity)

-- Delete reservation
redis.call('DEL', reservation_key)

return {1, "SUCCESS"}
```

### 3. Cancel Stock (TCC Cancel Phase)

```lua
-- cancel_stock.lua
-- KEYS[1] = inventory:{itemId}
-- KEYS[2] = reservation:{orderId}
-- ARGV[1] = quantity

local inventory_key = KEYS[1]
local reservation_key = KEYS[2]
local quantity = tonumber(ARGV[1])

-- Move from reserved back to available
redis.call('HINCRBY', inventory_key, 'reserved', -quantity)
redis.call('HINCRBY', inventory_key, 'available', quantity)

-- Delete reservation
redis.call('DEL', reservation_key)

return {1, "CANCELLED"}
```

## Outbox Pattern Implementation

### Database Schema

```sql
-- Outbox table for publishing events
CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(64) NOT NULL,     -- itemId or orderId
    event_type VARCHAR(50) NOT NULL,       -- inventory.reserved, inventory.released, inventory.sold
    payload JSONB NOT NULL,                -- Event details
    status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, SENT, FAILED
    created_at TIMESTAMP DEFAULT NOW(),
    sent_at TIMESTAMP,
    retry_count INT DEFAULT 0,
    error_message TEXT,
    INDEX idx_outbox_status (status),
    INDEX idx_outbox_created (created_at)
);

-- Consumer dedupe table for idempotency
CREATE TABLE consumer_dedupe (
    consumer_name VARCHAR(100) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (consumer_name, message_id),
    INDEX idx_processed_at (processed_at)
);

-- Inventory outbox for Redis operation recovery
CREATE TABLE redis_outbox (
    id BIGSERIAL PRIMARY KEY,
    operation_type VARCHAR(50) NOT NULL,   -- RESERVE, CONFIRM, CANCEL
    item_id VARCHAR(64) NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT NOW(),
    processed_at TIMESTAMP,
    retry_count INT DEFAULT 0,
    error_message TEXT,
    INDEX idx_redis_status (status),
    INDEX idx_redis_created (created_at)
);

### Updated Flow with Event Publishing

1. **Process Incoming Event (with Idempotency)**:
   ```java
   @RabbitListener(queues = "inventory.order.created")
   @Transactional
   public void handleOrderCreated(Message message) {
       String messageId = message.getMessageProperties().getMessageId();

       // Idempotency check
       if (dedupeRepo.existsByConsumerNameAndMessageId("InventoryOrderHandler", messageId)) {
           log.info("Duplicate message ignored: {}", messageId);
           return;
       }

       OrderCreatedEvent event = parseMessage(message);

       // Write Redis operation intent
       RedisOutbox redisOp = RedisOutbox.builder()
           .operationType("RESERVE")
           .itemId(event.getItemId())
           .orderId(event.getOrderId())
           .quantity(event.getQuantity())
           .status("PENDING")
           .build();
       redisOutboxRepo.save(redisOp);

       // Execute Redis operation
       try {
           reserveStock(event.getItemId(), event.getQuantity());
           redisOp.setStatus("PROCESSED");

           // Create outbound event
           outboxRepo.save(OutboxEvent.builder()
               .aggregateId(event.getOrderId())
               .eventType("inventory.reserved")
               .payload(buildReservedPayload(event))
               .build());
       } catch (Exception e) {
           redisOp.setStatus("FAILED");
           redisOp.setErrorMessage(e.getMessage());
       }

       // Mark message as processed
       dedupeRepo.save(new ConsumerDedupe("InventoryOrderHandler", messageId));
   }
   ```

2. **Background Redis Recovery Worker**:
   ```java
   @Scheduled(fixedDelay = 10000)
   public void recoverFailedRedisOperations() {
       List<RedisOutbox> failed = redisOutboxRepo
           .findByStatusAndCreatedBefore("FAILED",
               LocalDateTime.now().minusMinutes(1));

       for (RedisOutbox op : failed) {
           if (op.getRetryCount() > MAX_RETRIES) {
               op.setStatus("DEAD");
               // Send alert
               continue;
           }

           try {
               switch (op.getOperationType()) {
                   case "RESERVE":
                       reserveStock(op.getItemId(), op.getQuantity());
                       break;
                   case "CONFIRM":
                       confirmStock(op.getItemId(), op.getOrderId());
                       break;
                   case "CANCEL":
                       cancelStock(op.getItemId(), op.getOrderId());
                       break;
               }
               op.setStatus("PROCESSED");
           } catch (Exception e) {
               op.incrementRetryCount();
           }
           redisOutboxRepo.save(op);
       }
   }
   ```

### Event Publisher Worker

```java
@Component
public class InventoryEventPublisher {

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxRepo
            .findTop100ByStatusOrderByCreatedAt("PENDING");

        for (OutboxEvent event : pending) {
            try {
                Message message = MessageBuilder
                    .withBody(event.getPayload())
                    .setMessageId(event.getId().toString())
                    .setHeader("aggregate_id", event.getAggregateId())
                    .setHeader("event_type", event.getEventType())
                    .build();

                rabbitTemplate.send("lmall.events",
                    event.getEventType(), message);

                event.setStatus("SENT");
                event.setSentAt(LocalDateTime.now());
            } catch (Exception e) {
                event.incrementRetryCount();
                if (event.getRetryCount() > MAX_RETRIES) {
                    event.setStatus("FAILED");
                    alertService.sendAlert("Failed to publish event: " + event.getId());
                }
            }
            outboxRepo.save(event);
        }
    }
}
```

### Event Consumers Configuration

```java
@Configuration
public class InventoryMessageConfig {

    @Bean
    public Queue orderCreatedQueue() {
        return QueueBuilder.durable("inventory.order.created")
            .withArgument("x-dead-letter-exchange", "")
            .withArgument("x-dead-letter-routing-key", "inventory.order.created.dlq")
            .build();
    }

    @Bean
    public Queue orderCancelRequestedQueue() {
        return QueueBuilder.durable("inventory.order.cancel.requested")
            .withArgument("x-dead-letter-exchange", "")
            .withArgument("x-dead-letter-routing-key", "inventory.order.cancel.requested.dlq")
            .build();
    }

    @Bean
    public Queue orderConfirmedQueue() {
        return QueueBuilder.durable("inventory.order.confirmed")
            .withArgument("x-dead-letter-exchange", "")
            .withArgument("x-dead-letter-routing-key", "inventory.order.confirmed.dlq")
            .build();
    }

    @Bean
    public Binding orderCreatedBinding() {
        return BindingBuilder
            .bind(orderCreatedQueue())
            .to(eventsExchange())
            .with("order.created");
    }

    @Bean
    public Binding orderCancelBinding() {
        return BindingBuilder
            .bind(orderCancelRequestedQueue())
            .to(eventsExchange())
            .with("order.cancel.requested");
    }

    @Bean
    public Binding orderConfirmedBinding() {
        return BindingBuilder
            .bind(orderConfirmedQueue())
            .to(eventsExchange())
            .with("order.confirmed");
    }
}
```

## Performance Optimizations

### 1. Pipeline Operations
```java
// Batch multiple Redis operations
redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    for (String itemId : itemIds) {
        connection.hGetAll(("inventory:" + itemId).getBytes());
    }
    return null;
});
```

### 2. Connection Pooling
```yaml
spring:
  redis:
    lettuce:
      pool:
        max-active: 10
        max-idle: 8
        min-idle: 2
```

### 3. Prewarming Strategy
```java
@EventListener(ApplicationReadyEvent.class)
public void prewarmCache() {
    List<String> hotProducts = getHotProducts();
    for (String itemId : hotProducts) {
        Inventory inv = inventoryRepository.findById(itemId);
        cacheInventory(inv);
    }
}
```

## Monitoring & Alerts

### Key Metrics to Track

1. **Stock Levels**:
   ```sql
   SELECT itemId, available, reserved, sold
   FROM inventory
   WHERE available < 10;  -- Low stock alert
   ```

2. **Reservation Timeouts**:
   ```redis
   # Count expired reservations
   SCAN 0 MATCH reservation:* COUNT 1000
   ```

3. **Outbox Health**:
   ```sql
   SELECT status, COUNT(*)
   FROM inventory_outbox
   WHERE created_at > NOW() - INTERVAL '1 hour'
   GROUP BY status;
   ```

## Error Handling

### Common Scenarios

1. **Redis Connection Lost**:
   - Fall back to database
   - Queue operations in outbox
   - Alert operations team

2. **Lua Script Failure**:
   - Log with correlation ID
   - Return graceful error
   - Trigger compensation if needed

3. **Reservation Timeout**:
   - Automatic cleanup via TTL
   - Background job for orphaned reservations
   - Notify order service

## Testing Strategy

### Unit Tests
- Mock Redis operations
- Test Lua script logic
- Validate outbox flow

### Integration Tests
- Use Testcontainers with Redis
- Test concurrent reservations
- Verify timeout handling

### Load Tests
- Simulate Black Friday traffic
- Test with 10,000 concurrent reservations
- Monitor Redis memory and CPU