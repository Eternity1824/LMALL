package com.lmall.orderservice.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 库存消息消费者
 * 处理来自RabbitMQ的库存相关消息
 * 注意：实际实现应该在inventory-service中，这里只是一个占位符
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryMessageConsumer {

    private final ObjectMapper objectMapper;

    /**
     * 处理库存更新消息
     * 注意：这个方法在实际项目中应该位于inventory-service中
     * @param message 消息内容
     */
    @RabbitListener(queues = "${rabbitmq.queues.inventory-update}")
    public void handleInventoryUpdate(String message) {
        try {
            log.info("Received inventory update message: {}", message);
            Map<String, Object> data = objectMapper.readValue(message, Map.class);
            
            String itemId = (String) data.get("itemId");
            int quantity = (int) data.get("quantity");
            
            // 这里应该调用inventory-service的方法来更新数据库中的库存
            // 在实际项目中，这个消费者应该位于inventory-service中
            log.info("Would update inventory in database for item: {}, quantity change: {}", itemId, quantity);
            
            // 模拟处理成功
            log.info("Successfully processed inventory update for item: {}", itemId);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize inventory update message: {}", message, e);
        } catch (Exception e) {
            log.error("Failed to process inventory update message: {}", message, e);
        }
    }
}
