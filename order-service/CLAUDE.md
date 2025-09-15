# Order Service - Development Tracking

## Overview
This document tracks all modifications and improvements made to the order-service module, with detailed explanations for learning purposes.

## Architecture Decisions
- **Communication**: Pure gRPC service (no REST endpoints)
- **Pattern**: TCC (Try-Confirm-Cancel) for distributed transactions
- **Storage**: PostgreSQL for persistent data, Redis for temporary TCC state
- **Messaging**: RabbitMQ for async event publishing

## Modifications Log

### 1. Fix Order Entity (Date: 2024-01-14)

**Problem**:
- Missing critical fields for e-commerce orders
- Referenced non-existent `updatedAt` field in service
- No audit trail or versioning

**Solution**: Added comprehensive fields
```java
// Before: Only basic fields
private String orderId, userId, itemId;
private int quantity;
private OrderStatus status;

// After: Complete e-commerce order model
+ private BigDecimal price;           // Item price
+ private BigDecimal totalAmount;     // Total = price * quantity
+ private String paymentId;           // Payment reference
+ private String shippingAddress;     // Delivery address
+ private LocalDateTime updatedAt;    // Last modification time
+ @Version Long version;              // Optimistic locking
+ // TCC specific fields
+ private TccState tccState;          // TRYING, CONFIRMED, CANCELLED
+ private LocalDateTime tryTimestamp; // When TCC started
+ private String tccTransactionId;    // Unique TCC transaction ID
```

**Learning Points**:
- `@Version` enables optimistic locking to prevent concurrent modifications
- `BigDecimal` for money to avoid floating-point precision issues
- TCC fields track distributed transaction state
- Timestamps help with timeout detection

### 2. Update POM Configuration

**Problem**:
- Java version mismatch (11 vs 21)
- Spring Boot version inconsistency
- Missing essential dependencies

**Changes Made**:
```xml
<!-- Updated Java version -->
<java.version>21</java.version>

<!-- Added new dependencies -->
+ spring-boot-starter-validation  // Bean validation
+ mapstruct                      // DTO mapping
+ resilience4j                   // Circuit breaker
```

**Learning Points**:
- Java 21 enables virtual threads and pattern matching
- Validation starter provides @Valid, @NotNull annotations
- MapStruct generates mapping code at compile time (faster than reflection)
- Resilience4j handles failures gracefully

### 3. Create DTOs with Validation

**Purpose**: Separate API contract from internal model

**Created Files**:
- `CreateOrderRequest.java` - Input validation
- `OrderResponse.java` - Output formatting
- `TccTransactionRequest.java` - TCC operations

**Example with explanations**:
```java
public class CreateOrderRequest {
    @NotNull(message = "User ID is required")
    private String userId;

    @NotNull @Min(1) // Validation rules
    private Integer quantity;

    @DecimalMin("0.01") // Price must be positive
    private BigDecimal price;
}
```

**Learning Points**:
- DTOs prevent exposing internal structure
- Validation at boundary prevents bad data
- Custom error messages improve debugging

### 4. Exception Handling Architecture

**Created Global Exception Handler**:
```java
@ControllerAdvice  // Catches all exceptions
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    @ResponseStatus(NOT_FOUND)
    public ErrorResponse handleNotFound(OrderNotFoundException e) {
        // Convert exception to user-friendly response
    }
}
```

**Custom Exceptions Created**:
- `OrderNotFoundException` - 404 scenarios
- `InvalidOrderStateException` - Business rule violations
- `TccTimeoutException` - Distributed transaction timeouts
- `InventoryNotAvailableException` - Stock issues

**Learning Points**:
- Centralized error handling reduces duplication
- Specific exceptions make debugging easier
- Proper HTTP status codes for different errors

### 5. Service Layer Improvements

**Before**: Basic CRUD with no error handling
**After**: Robust business logic with proper patterns

**Key Improvements**:
```java
// Proper error handling
public Order getOrder(String orderId) {
    return orderRepo.findById(orderId)
        .orElseThrow(() -> new OrderNotFoundException(orderId));
}

// Business validation
private void validateOrderCreation(CreateOrderRequest request) {
    // Check business rules
    if (request.getQuantity() > MAX_ORDER_QUANTITY) {
        throw new InvalidOrderException("Quantity exceeds limit");
    }
}

// Idempotency support
if (orderRepo.existsById(orderId)) {
    log.warn("Order {} already exists, returning existing", orderId);
    return orderRepo.findById(orderId).get();
}
```

**Learning Points**:
- Never return null - throw exceptions
- Validate business rules explicitly
- Support idempotent operations for reliability

### 6. Repository Enhancements

**Added Custom Queries**:
```java
public interface OrderRepository extends JpaRepository<Order, String> {
    // Find orders in TCC trying state older than timeout
    @Query("SELECT o FROM Order o WHERE o.tccState = 'TRYING' " +
           "AND o.tryTimestamp < :timeout")
    List<Order> findTimedOutTryingOrders(@Param("timeout") LocalDateTime timeout);

    // User's orders with pagination
    Page<Order> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    // Concurrent-safe status update
    @Modifying
    @Query("UPDATE Order o SET o.status = :status, o.version = o.version + 1 " +
           "WHERE o.orderId = :orderId AND o.version = :version")
    int updateStatusWithVersion(@Param("orderId") String orderId,
                               @Param("status") OrderStatus status,
                               @Param("version") Long version);
}
```

**Learning Points**:
- JPQL queries for complex operations
- Pagination for large result sets
- Version checking prevents lost updates

## TCC Implementation Plan

### Phase 1: State Management ✅
- Added TCC fields to entity
- Created state transition validators

### Phase 2: Outbox Pattern (Next)
- Create OutboxEvent entity
- Implement transactional outbox
- Add retry mechanism

### Phase 3: Timeout Handling
- Scheduled job for timeout detection
- Automatic cancellation of expired transactions

### Phase 4: Compensation
- Implement rollback strategies
- Add compensation logging

## Testing Strategy

### Unit Tests
- Service layer with mocked dependencies
- TCC state transitions
- Validation rules

### Integration Tests
- Repository with @DataJpaTest
- Full TCC flow with TestContainers

### Performance Tests
- Concurrent order creation
- TCC timeout scenarios

## Configuration Best Practices

### Application Profiles
```yaml
# application-dev.yml - Local development
spring.jpa.show-sql: true

# application-prod.yml - Production
spring.jpa.show-sql: false
```

### Security
- Never commit credentials
- Use environment variables
- Implement secrets management

## Common Pitfalls Avoided

1. **Race Conditions**: Used optimistic locking
2. **Null Returns**: Throw exceptions instead
3. **Money Precision**: BigDecimal not double
4. **Transaction Boundaries**: Proper @Transactional usage
5. **N+1 Queries**: Eager fetching where needed

## Next Steps

1. Implement Outbox pattern for reliability
2. Add metrics and monitoring
3. Create gRPC service methods
4. Implement TCC coordinator
5. Add distributed tracing

## Commands for Development

```bash
# Build service
mvn clean install

# Run tests
mvn test

# Generate MapStruct mappers
mvn compile

# Start service
mvn spring-boot:run
```

## Monitoring Queries

```sql
-- Find stuck TCC transactions
SELECT * FROM orders
WHERE tcc_state = 'TRYING'
AND try_timestamp < NOW() - INTERVAL '5 minutes';

-- Check order distribution
SELECT status, COUNT(*)
FROM orders
GROUP BY status;
```