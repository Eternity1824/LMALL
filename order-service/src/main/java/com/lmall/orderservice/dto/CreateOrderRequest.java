package com.lmall.orderservice.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Create Order Request DTO
 *
 * Validation Rules:
 * - @NotNull: Field cannot be null
 * - @NotBlank: String cannot be null or empty
 * - @Min/@Max: Numeric boundaries
 * - @DecimalMin: Minimum value for BigDecimal
 * - Custom messages for better debugging
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderRequest {

    @NotBlank(message = "User ID is required")
    @Size(max = 64, message = "User ID cannot exceed 64 characters")
    private String userId;

    @NotBlank(message = "Item ID is required")
    @Size(max = 64, message = "Item ID cannot exceed 64 characters")
    private String itemId;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    @Max(value = 100, message = "Quantity cannot exceed 100")
    private Integer quantity;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.01", message = "Price must be at least 0.01")
    @DecimalMax(value = "999999.99", message = "Price cannot exceed 999999.99")
    private BigDecimal price;

    @NotBlank(message = "Shipping address is required")
    @Size(max = 500, message = "Shipping address cannot exceed 500 characters")
    private String shippingAddress;

    // Optional: for idempotency
    @Size(max = 64, message = "Idempotency key cannot exceed 64 characters")
    private String idempotencyKey;
}