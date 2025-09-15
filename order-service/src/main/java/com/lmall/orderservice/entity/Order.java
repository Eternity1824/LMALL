package com.lmall.orderservice.entity;

import com.lmall.orderservice.enums.OrderStatus;
import com.lmall.orderservice.enums.TccState;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Order Entity
 *
 * Key Design Decisions:
 * - BigDecimal for money fields (avoid floating-point errors)
 * - @Version for optimistic locking (prevent concurrent updates)
 * - TCC fields for distributed transaction tracking
 * - Comprehensive audit fields
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "orders", indexes = {
    @Index(name = "idx_user_id", columnList = "userId"),
    @Index(name = "idx_tcc_state", columnList = "tccState"),
    @Index(name = "idx_created_at", columnList = "createdAt")
})
public class Order {

    @Id
    @Column(length = 64)
    private String orderId;

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, length = 64)
    private String itemId;

    @Column(nullable = false)
    private Integer quantity;

    // Money fields - always use BigDecimal for currency
    @Column(precision = 19, scale = 2)
    private BigDecimal price;  // Unit price

    @Column(precision = 19, scale = 2)
    private BigDecimal totalAmount;  // price * quantity

    // Order status and state
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    // TCC transaction fields
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private TccState tccState;

    @Column(length = 64)
    private String tccTransactionId;  // Unique ID for this TCC transaction

    private LocalDateTime tryTimestamp;  // When TCC try phase started

    private LocalDateTime confirmTimestamp;  // When order was confirmed

    // Payment and shipping
    @Column(length = 64)
    private String paymentId;

    @Column(length = 500)
    private String shippingAddress;

    // Audit fields
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // Optimistic locking - prevents lost updates
    @Version
    private Long version;

    // Lifecycle hooks
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (tccState == null) {
            tccState = TccState.TRYING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
