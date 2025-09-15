package com.lmall.orderservice.exception;

import com.lmall.orderservice.enums.OrderStatus;
import com.lmall.orderservice.enums.TccState;

/**
 * Exception for invalid order state transitions
 *
 * Examples:
 * - Trying to confirm an already cancelled order
 * - Attempting to ship before payment
 */
public class InvalidOrderStateException extends RuntimeException {

    private final String orderId;
    private final String currentState;
    private final String targetState;

    public InvalidOrderStateException(String orderId, OrderStatus current, OrderStatus target) {
        super(String.format("Invalid state transition for order %s: %s -> %s",
                orderId, current, target));
        this.orderId = orderId;
        this.currentState = current.name();
        this.targetState = target.name();
    }

    public InvalidOrderStateException(String orderId, TccState current, TccState target) {
        super(String.format("Invalid TCC state transition for order %s: %s -> %s",
                orderId, current, target));
        this.orderId = orderId;
        this.currentState = current.name();
        this.targetState = target.name();
    }

    public String getOrderId() {
        return orderId;
    }

    public String getCurrentState() {
        return currentState;
    }

    public String getTargetState() {
        return targetState;
    }
}