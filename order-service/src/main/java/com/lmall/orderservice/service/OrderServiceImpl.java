package com.lmall.orderservice.service;

import com.lmall.orderservice.entity.Order;
import com.lmall.orderservice.enums.OrderStatus;
import com.lmall.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepo;

    @Override
    @Transactional
    public Order createOrder(String orderId, String userId, String itemId, int quantity, OrderStatus status) {
        Order order = new Order();
        order.setOrderId(orderId);
        order.setUserId(userId);
        order.setItemId(itemId);
        order.setQuantity(quantity);
        order.setStatus(status);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());

        return orderRepo.save(order);
    }

    @Override
    public Order getOrder(String orderId) {
        return orderRepo.findById(orderId).orElse(null);
    }

    @Override
    @Transactional
    public void updateOrderStatus(String orderId, OrderStatus status) {
        orderRepo.findById(orderId).ifPresent(order -> {
            order.setStatus(status);
            order.setUpdatedAt(LocalDateTime.now());
            orderRepo.save(order);
        });
    }
}
