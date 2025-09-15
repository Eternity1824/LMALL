package com.lmall.orderservice.service;

import com.lmall.orderservice.entity.Order;
import com.lmall.orderservice.enums.OrderStatus;
import com.lmall.orderservice.exception.InvalidOrderStateException;
import com.lmall.orderservice.exception.OrderNotFoundException;
import com.lmall.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order Service Implementation
 *
 * Key improvements:
 * - Proper exception handling (no null returns)
 * - Logging for debugging
 * - Idempotency support
 * - State validation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepo;

    @Override
    @Transactional
    public Order createOrder(String orderId, String userId, String itemId, int quantity, OrderStatus status) {
        log.info("Creating order: {} for user: {}", orderId, userId);

        // Idempotency check - if order exists, return it
        if (orderRepo.existsById(orderId)) {
            log.warn("Order {} already exists, returning existing order", orderId);
            return orderRepo.findById(orderId)
                    .orElseThrow(() -> new OrderNotFoundException(orderId));
        }

        // Create new order
        Order order = Order.builder()
                .orderId(orderId)
                .userId(userId)
                .itemId(itemId)
                .quantity(quantity)
                .status(status != null ? status : OrderStatus.PENDING)
                .build();

        Order savedOrder = orderRepo.save(order);
        log.info("Order created successfully: {}", orderId);

        return savedOrder;
    }

    @Override
    @Transactional(readOnly = true)
    public Order getOrder(String orderId) {
        log.debug("Fetching order: {}", orderId);

        return orderRepo.findById(orderId)
                .orElseThrow(() -> {
                    log.error("Order not found: {}", orderId);
                    return new OrderNotFoundException(orderId);
                });
    }

    @Override
    @Transactional
    public void updateOrderStatus(String orderId, OrderStatus newStatus) {
        log.info("Updating order {} status to {}", orderId, newStatus);

        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        // Validate state transition
        if (!isValidStateTransition(order.getStatus(), newStatus)) {
            throw new InvalidOrderStateException(orderId, order.getStatus(), newStatus);
        }

        order.setStatus(newStatus);
        orderRepo.save(order);

        log.info("Order {} status updated to {}", orderId, newStatus);
    }

    /**
     * Validate if state transition is allowed
     *
     * State machine rules:
     * PENDING -> CONFIRMED, CANCELLED
     * CONFIRMED -> PROCESSING, CANCELLED
     * PROCESSING -> SHIPPED, CANCELLED
     * SHIPPED -> DELIVERED
     * DELIVERED -> (terminal state)
     * CANCELLED -> (terminal state)
     */
    private boolean isValidStateTransition(OrderStatus current, OrderStatus target) {
        if (current == null || target == null) {
            return false;
        }

        return switch (current) {
            case PENDING -> target == OrderStatus.CONFIRMED || target == OrderStatus.CANCELLED;
            case CONFIRMED -> target == OrderStatus.PROCESSING || target == OrderStatus.CANCELLED;
            case PROCESSING -> target == OrderStatus.SHIPPED || target == OrderStatus.CANCELLED;
            case SHIPPED -> target == OrderStatus.DELIVERED;
            case DELIVERED, CANCELLED -> false; // Terminal states
            default -> false;
        };
    }
}