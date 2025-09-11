package com.lmall.inventoryservice.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmall.inventoryservice.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 库存消息消费者
 * 处理来自mq-service的库存相关消息
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryMessageConsumer {

    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;

    /**
     * 处理库存更新消息
     * @param message 消息内容
     */
    @RabbitListener(queues = "${rabbitmq.queues.inventory-update}")
    public void handleInventoryUpdate(String message) {
        try {
            log.info("Received inventory update message: {}", message);
            Map<String, Object> data = objectMapper.readValue(message, Map.class);
            
            String itemId = (String) data.get("itemId");
            int quantity = (int) data.get("quantity");
            
            // 1. 更新数据库中的库存
            boolean dbUpdateSuccess = inventoryService.updateInventoryInDatabase(itemId, quantity);
            
            // 2. 如果是减少库存（下单），则更新预留库存
            if (dbUpdateSuccess && quantity < 0) {
                inventoryService.updateReservedInventoryInDatabase(itemId, quantity);
            }
            
            // 3. 同步更新Redis中的库存（预热）
            // 注意：这里我们不直接更新Redis，而是重新从数据库加载最新数据
            // 这样可以确保Redis和数据库的一致性
            inventoryService.getInventory(itemId).ifPresent(inventory -> {
                boolean redisUpdateSuccess = inventoryService.rollbackInventory(itemId, 0); // 触发Redis重新加载
                log.info("Redis inventory sync result: {}", redisUpdateSuccess);
            });
            
            log.info("Successfully processed inventory update for item: {}, quantity change: {}", itemId, quantity);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize inventory update message: {}", message, e);
        } catch (Exception e) {
            log.error("Failed to process inventory update message: {}", message, e);
        }
    }
    
    /**
     * 处理库存预热消息
     * @param message 消息内容
     */
    @RabbitListener(queues = "${rabbitmq.queues.inventory-prewarm}")
    public void handleInventoryPrewarm(String message) {
        try {
            log.info("Received inventory prewarm message: {}", message);
            Map<String, Object> data = objectMapper.readValue(message, Map.class);
            
            String itemId = (String) data.get("itemId");
            
            // 预热指定商品的库存到Redis
            inventoryService.getInventory(itemId).ifPresent(inventory -> {
                String inventoryKey = "product:inventory:" + itemId;
                // 直接设置Redis中的库存为可用库存
                boolean success = inventoryService.rollbackInventory(itemId, 0); // 触发Redis重新加载
                log.info("Prewarmed inventory for item: {}, success: {}", itemId, success);
            });
            
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize inventory prewarm message: {}", message, e);
        } catch (Exception e) {
            log.error("Failed to process inventory prewarm message: {}", message, e);
        }
    }
}
