package com.lmall.orderservice.service.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmall.orderservice.entity.Order;
import com.lmall.orderservice.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ消息服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RabbitMQMessageService implements MessageService {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    
    @Value("${rabbitmq.exchanges.order}")
    private String orderExchange;
    
    @Value("${rabbitmq.routing-keys.order-creation}")
    private String orderCreationRoutingKey;
    
    @Value("${rabbitmq.routing-keys.order-status-update}")
    private String orderStatusUpdateRoutingKey;

    @Override
    public void sendOrderCreationMessage(Order order) {
        try {
            String message = objectMapper.writeValueAsString(order);
            rabbitTemplate.convertAndSend(orderExchange, orderCreationRoutingKey, message);
            log.info("Sent order creation message for order: {}", order.getOrderId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize order: {}", order.getOrderId(), e);
            throw new RuntimeException("Failed to send order creation message", e);
        }
    }

    @Override
    public void sendOrderStatusUpdateMessage(String orderId, OrderStatus status) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("orderId", orderId);
            message.put("status", status.name());
            
            String messageJson = objectMapper.writeValueAsString(message);
            rabbitTemplate.convertAndSend(orderExchange, orderStatusUpdateRoutingKey, messageJson);
            log.info("Sent order status update message for order: {}, status: {}", orderId, status);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize order status update for order: {}", orderId, e);
            throw new RuntimeException("Failed to send order status update message", e);
        }
    }
}
