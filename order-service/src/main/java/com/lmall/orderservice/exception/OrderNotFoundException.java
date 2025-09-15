package com.lmall.orderservice.exception;

/**
 * Exception thrown when an order is not found
 *
 * Runtime exception because:
 * - Cleaner code (no forced try-catch)
 * - Business logic exceptions should be unchecked
 * - Spring handles runtime exceptions well
 */
public class OrderNotFoundException extends RuntimeException {

    private final String orderId;

    public OrderNotFoundException(String orderId) {
        super(String.format("Order not found: %s", orderId));
        this.orderId = orderId;
    }

    public OrderNotFoundException(String orderId, Throwable cause) {
        super(String.format("Order not found: %s", orderId), cause);
        this.orderId = orderId;
    }

    public String getOrderId() {
        return orderId;
    }
}