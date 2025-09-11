package com.lmall.inventoryservice.service;

import com.lmall.inventoryservice.entity.Inventory;
import com.lmall.inventoryservice.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 库存预热服务
 * 负责将数据库中的库存数据预热到Redis中，用于高并发场景
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryPrewarmService {

    private final InventoryRepository inventoryRepository;
    private final StringRedisTemplate redisTemplate;
    
    private static final String INVENTORY_KEY_PREFIX = "product:inventory:";

    /**
     * 初始化时预热库存数据到Redis
     */
    public void prewarmInventory() {
        log.info("Starting inventory prewarm process");
        List<Inventory> allInventory = inventoryRepository.findAll();
        
        for (Inventory inventory : allInventory) {
            String inventoryKey = INVENTORY_KEY_PREFIX + inventory.getItemId();
            redisTemplate.opsForValue().set(inventoryKey, String.valueOf(inventory.getAvailable()));
            log.info("Prewarmed inventory for item: {}, available: {}", 
                    inventory.getItemId(), inventory.getAvailable());
        }
        
        log.info("Completed inventory prewarm process, total items: {}", allInventory.size());
    }
    
    /**
     * 定时同步数据库库存到Redis
     * 每小时执行一次
     */
    @Scheduled(fixedRate = 3600000) // 1小时
    public void scheduledInventorySync() {
        log.info("Starting scheduled inventory sync");
        prewarmInventory();
    }
    
    /**
     * 为秒杀活动预热特定商品库存
     * @param itemId 商品ID
     * @return 是否预热成功
     */
    public boolean prewarmItemForSeckill(String itemId) {
        log.info("Prewarming item for seckill: {}", itemId);
        
        return inventoryRepository.findById(itemId).map(inventory -> {
            String inventoryKey = INVENTORY_KEY_PREFIX + itemId;
            redisTemplate.opsForValue().set(inventoryKey, String.valueOf(inventory.getAvailable()));
            log.info("Successfully prewarmed item for seckill: {}, available: {}", 
                    itemId, inventory.getAvailable());
            return true;
        }).orElseGet(() -> {
            log.warn("Failed to prewarm item for seckill, item not found: {}", itemId);
            return false;
        });
    }
}
