package com.lmall.orderservice.service;

import com.lmall.orderservice.entity.Order;
import com.lmall.orderservice.enums.OrderStatus;

public interface OrderService {
    Order createOrder(String orderId, String userId, String itemId, int quantity, OrderStatus status);
    Order getOrder(String orderId);
    void updateOrderStatus(String orderId, OrderStatus status);
}
