package com.lmall.orderservice.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmall.orderservice.entity.Order;
import com.lmall.orderservice.enums.OrderStatus;
import com.lmall.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 订单消息消费者
 * 处理来自RabbitMQ的订单相关消息
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderMessageConsumer {

    private final OrderService orderService;
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
            
            // 持久化订单到数据库
            orderService.createOrder(
                    order.getOrderId(),
                    order.getUserId(),
                    order.getItemId(),
                    order.getQuantity(),
                    order.getStatus()
            );
            
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
            orderService.updateOrderStatus(orderId, status);
            
            log.info("Successfully updated order status: {}, status: {}", orderId, status);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize order status update message: {}", message, e);
        } catch (Exception e) {
            log.error("Failed to process order status update message: {}", message, e);
        }
    }
}
