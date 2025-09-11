package com.lmall.inventoryservice.service;

import com.lmall.inventoryservice.entity.Inventory;
import com.lmall.inventoryservice.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Optional;

/**
 * 库存服务
 * 负责库存的查询、预留和回滚
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final StringRedisTemplate redisTemplate;
    
    private static final String INVENTORY_KEY_PREFIX = "product:inventory:";
    
    // Redis Lua脚本：原子性地检查并减少库存
    private static final String DECREMENT_INVENTORY_LUA = 
            "local current = tonumber(redis.call('get', KEYS[1])) " +
            "if current == nil then return -1 end " +
            "if current >= tonumber(ARGV[1]) then " +
            "  redis.call('decrby', KEYS[1], ARGV[1]) " +
            "  return 1 " +
            "else " +
            "  return 0 " +
            "end";
    
    // Redis Lua脚本：增加库存
    private static final String INCREMENT_INVENTORY_LUA = 
            "local current = tonumber(redis.call('get', KEYS[1])) " +
            "if current == nil then " +
            "  redis.call('set', KEYS[1], ARGV[1]) " +
            "else " +
            "  redis.call('incrby', KEYS[1], ARGV[1]) " +
            "end " +
            "return 1";
    
    /**
     * 从数据库获取商品库存
     * @param itemId 商品ID
     * @return 库存信息
     */
    public Optional<Inventory> getInventory(String itemId) {
        return inventoryRepository.findById(itemId);
    }
    
    /**
     * 从Redis获取商品可用库存
     * @param itemId 商品ID
     * @return 可用库存数量，如果不存在则返回-1
     */
    public int getAvailableInventory(String itemId) {
        String inventoryKey = INVENTORY_KEY_PREFIX + itemId;
        String stockStr = redisTemplate.opsForValue().get(inventoryKey);
        
        if (stockStr == null) {
            // 如果Redis中不存在，尝试从数据库加载
            return getInventory(itemId)
                    .map(inventory -> {
                        // 预热到Redis
                        redisTemplate.opsForValue().set(inventoryKey, String.valueOf(inventory.getAvailable()));
                        return inventory.getAvailable();
                    })
                    .orElse(-1);
        }
        
        return Integer.parseInt(stockStr);
    }
    
    /**
     * 尝试预留库存（Redis原子操作）
     * @param itemId 商品ID
     * @param quantity 数量
     * @return 是否预留成功
     */
    public boolean tryReserveInventory(String itemId, int quantity) {
        String inventoryKey = INVENTORY_KEY_PREFIX + itemId;
        
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(DECREMENT_INVENTORY_LUA, Long.class);
        Long result = redisTemplate.execute(script, Collections.singletonList(inventoryKey), String.valueOf(quantity));
        
        if (result == null) {
            log.error("Failed to execute Redis script for item: {}", itemId);
            return false;
        }
        
        boolean success = result == 1;
        if (success) {
            log.info("Successfully reserved inventory for item: {}, quantity: {}", itemId, quantity);
        } else {
            log.warn("Failed to reserve inventory for item: {}, quantity: {}, result: {}", itemId, quantity, result);
        }
        
        return success;
    }
    
    /**
     * 回滚库存（Redis原子操作）
     * @param itemId 商品ID
     * @param quantity 数量
     * @return 是否回滚成功
     */
    public boolean rollbackInventory(String itemId, int quantity) {
        String inventoryKey = INVENTORY_KEY_PREFIX + itemId;
        
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(INCREMENT_INVENTORY_LUA, Long.class);
        Long result = redisTemplate.execute(script, Collections.singletonList(inventoryKey), String.valueOf(quantity));
        
        boolean success = result != null && result == 1;
        if (success) {
            log.info("Successfully rolled back inventory for item: {}, quantity: {}", itemId, quantity);
        } else {
            log.error("Failed to roll back inventory for item: {}, quantity: {}", itemId, quantity);
        }
        
        return success;
    }
    
    /**
     * 更新数据库中的库存数量
     * @param itemId 商品ID
     * @param quantity 数量变化
     * @return 是否更新成功
     */
    @Transactional
    public boolean updateInventoryInDatabase(String itemId, int quantity) {
        try {
            int updated = inventoryRepository.updateInventoryQuantity(itemId, quantity);
            boolean success = updated > 0;
            
            if (success) {
                log.info("Successfully updated inventory in database for item: {}, quantity change: {}", itemId, quantity);
            } else {
                log.warn("Failed to update inventory in database for item: {}, item may not exist", itemId);
            }
            
            return success;
        } catch (Exception e) {
            log.error("Error updating inventory in database for item: {}", itemId, e);
            return false;
        }
    }
    
    /**
     * 更新数据库中的预留库存
     * @param itemId 商品ID
     * @param reserved 预留数量变化
     * @return 是否更新成功
     */
    @Transactional
    public boolean updateReservedInventoryInDatabase(String itemId, int reserved) {
        try {
            int updated = inventoryRepository.updateReservedInventory(itemId, reserved);
            boolean success = updated > 0;
            
            if (success) {
                log.info("Successfully updated reserved inventory in database for item: {}, reserved change: {}", itemId, reserved);
            } else {
                log.warn("Failed to update reserved inventory in database for item: {}, item may not exist", itemId);
            }
            
            return success;
        } catch (Exception e) {
            log.error("Error updating reserved inventory in database for item: {}", itemId, e);
            return false;
        }
    }
}
