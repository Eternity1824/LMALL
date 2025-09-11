package com.lmall.mqservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmall.mqservice.entity.Inventory;
import com.lmall.mqservice.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 库存消息处理服务
 * 负责处理来自order-service的库存相关消息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryMessageHandler {

    private final InventoryRepository inventoryRepository;
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
            int quantityChange = (int) data.get("quantity");
            
            // 更新库存
            inventoryRepository.findById(itemId).ifPresentOrElse(
                inventory -> {
                    // 更新现有库存
                    updateExistingInventory(inventory, quantityChange);
                },
                () -> {
                    // 如果库存不存在且是增加库存，则创建新库存
                    if (quantityChange > 0) {
                        createNewInventory(itemId, quantityChange);
                    } else {
                        log.warn("Cannot decrease inventory for non-existent item: {}", itemId);
                    }
                }
            );
            
            log.info("Successfully processed inventory update for item: {}", itemId);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize inventory update message: {}", message, e);
        } catch (Exception e) {
            log.error("Failed to process inventory update message: {}", message, e);
        }
    }
    
    /**
     * 更新现有库存
     * @param inventory 库存实体
     * @param quantityChange 数量变化
     */
    private void updateExistingInventory(Inventory inventory, int quantityChange) {
        inventory.setQuantity(inventory.getQuantity() + quantityChange);
        
        // 如果是减少库存，则减少预留数量
        if (quantityChange < 0) {
            inventory.setReserved(Math.max(0, inventory.getReserved() + quantityChange));
        }
        
        // 计算可用库存
        inventory.setAvailable(inventory.getQuantity() - inventory.getReserved());
        
        inventoryRepository.save(inventory);
        log.info("Updated inventory for item: {}, new quantity: {}, available: {}", 
                inventory.getItemId(), inventory.getQuantity(), inventory.getAvailable());
    }
    
    /**
     * 创建新库存
     * @param itemId 商品ID
     * @param quantity 数量
     */
    private void createNewInventory(String itemId, int quantity) {
        Inventory inventory = new Inventory();
        inventory.setItemId(itemId);
        inventory.setQuantity(quantity);
        inventory.setReserved(0);
        inventory.setAvailable(quantity);
        
        inventoryRepository.save(inventory);
        log.info("Created new inventory for item: {}, quantity: {}", itemId, quantity);
    }
}
