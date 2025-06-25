package com.lmall.orderservice.service;

public interface OrderService {
    String createOrder(String userId, String itemId, int quantity);
}
