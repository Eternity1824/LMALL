package com.lmall.mqservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmall.mqservice.entity.Order;
import com.lmall.mqservice.entity.OrderStatus;
import com.lmall.mqservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 订单消息处理服务
 * 负责处理来自order-service的订单相关消息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderMessageHandler {

    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    /**
     * 处理订单创建消息
     * @param message 消息内容
     */
    @RabbitListener(queues = "${rabbitmq.queues.order-creation}")
    public void handleOrderCreation(String message) {
        try {
            log.info("Received order creation message: {}", message);
            Order order = objectMapper.readValue(message, Order.class);
            
            // 设置更新时间
            if (order.getCreatedAt() == null) {
                order.setCreatedAt(LocalDateTime.now());
            }
            order.setUpdatedAt(LocalDateTime.now());
            
            // 持久化订单到数据库
            orderRepository.save(order);
            
            log.info("Successfully persisted order to database: {}", order.getOrderId());
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize order creation message: {}", message, e);
        } catch (Exception e) {
            log.error("Failed to process order creation message: {}", message, e);
        }
    }

    /**
     * 处理订单状态更新消息
     * @param message 消息内容
     */
    @RabbitListener(queues = "${rabbitmq.queues.order-status-update}")
    public void handleOrderStatusUpdate(String message) {
        try {
            log.info("Received order status update message: {}", message);
            Map<String, Object> data = objectMapper.readValue(message, Map.class);
            
            String orderId = (String) data.get("orderId");
            String statusStr = (String) data.get("status");
            OrderStatus status = OrderStatus.valueOf(statusStr);
            
            // 更新订单状态
            orderRepository.findById(orderId).ifPresent(order -> {
                order.setStatus(status);
                order.setUpdatedAt(LocalDateTime.now());
                orderRepository.save(order);
                log.info("Successfully updated order status: {}, status: {}", orderId, status);
            });
            
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize order status update message: {}", message, e);
        } catch (Exception e) {
            log.error("Failed to process order status update message: {}", message, e);
        }
    }
}
