package com.lmall.mqservice.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 库存预热消息消费者
 * 接收库存预热请求并转发到inventory-service
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryPrewarmConsumer {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    
    @Value("${rabbitmq.exchanges.inventory}")
    private String inventoryExchange;
    
    @Value("${rabbitmq.routing-keys.inventory-prewarm}")
    private String inventoryPrewarmRoutingKey;

    /**
     * 处理库存预热消息
     * 接收预热请求并转发到inventory-service
     * @param message 消息内容
     */
    @RabbitListener(queues = "${rabbitmq.queues.inventory-prewarm}")
    public void handleInventoryPrewarm(String message) {
        try {
            log.info("Received inventory prewarm message: {}", message);
            Map<String, Object> data = objectMapper.readValue(message, Map.class);
            
            // 转发消息到inventory-service
            rabbitTemplate.convertAndSend(inventoryExchange, inventoryPrewarmRoutingKey, message);
            
            String itemId = (String) data.get("itemId");
            log.info("Forwarded inventory prewarm message for item: {}", itemId);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize inventory prewarm message: {}", message, e);
        } catch (Exception e) {
            log.error("Failed to process inventory prewarm message: {}", message, e);
        }
    }
}
