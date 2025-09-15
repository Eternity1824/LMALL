package com.lmall.orderservice.dto;

import com.lmall.orderservice.enums.OrderStatus;
import com.lmall.orderservice.enums.TccState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Order Response DTO
 *
 * Purpose: Control what data is exposed to clients
 * - Excludes internal fields like version
 * - Formats dates consistently
 * - Can add computed fields
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {

    private String orderId;
    private String userId;
    private String itemId;
    private Integer quantity;
    private BigDecimal price;
    private BigDecimal totalAmount;
    private OrderStatus status;
    private String shippingAddress;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // TCC information (only for internal/admin use)
    private TccState tccState;
    private String tccTransactionId;

    // Computed field example
    public boolean isConfirmed() {
        return tccState == TccState.CONFIRMED;
    }

    // Can add display-friendly methods
    public String getStatusDisplay() {
        return status != null ? status.name().replace("_", " ") : "UNKNOWN";
    }
}