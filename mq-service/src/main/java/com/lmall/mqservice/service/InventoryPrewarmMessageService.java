package com.lmall.mqservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 库存预热消息服务
 * 负责发送库存预热相关消息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryPrewarmMessageService {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    
    @Value("${rabbitmq.exchanges.inventory}")
    private String inventoryExchange;
    
    @Value("${rabbitmq.routing-keys.inventory-prewarm}")
    private String inventoryPrewarmRoutingKey;

    /**
     * 发送库存预热消息
     * @param itemId 商品ID
     * @return 是否发送成功
     */
    public boolean sendInventoryPrewarmMessage(String itemId) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("itemId", itemId);
            message.put("timestamp", System.currentTimeMillis());
            
            String messageJson = objectMapper.writeValueAsString(message);
            rabbitTemplate.convertAndSend(inventoryExchange, inventoryPrewarmRoutingKey, messageJson);
            
            log.info("Sent inventory prewarm message for item: {}", itemId);
            return true;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize inventory prewarm message for item: {}", itemId, e);
            return false;
        } catch (Exception e) {
            log.error("Failed to send inventory prewarm message for item: {}", itemId, e);
            return false;
        }
    }
    
    /**
     * 发送批量库存预热消息
     * @param itemIds 商品ID列表
     * @return 成功预热的商品数量
     */
    public int sendBatchInventoryPrewarmMessages(Iterable<String> itemIds) {
        int successCount = 0;
        
        for (String itemId : itemIds) {
            if (sendInventoryPrewarmMessage(itemId)) {
                successCount++;
            }
        }
        
        log.info("Sent batch inventory prewarm messages, success count: {}", successCount);
        return successCount;
    }
}
