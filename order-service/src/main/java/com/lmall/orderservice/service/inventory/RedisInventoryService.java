package com.lmall.orderservice.service.inventory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * Redis实现的库存服务
 * 使用Redis Lua脚本保证库存操作的原子性
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisInventoryService implements InventoryService {

    private final StringRedisTemplate redisTemplate;
    
    private static final String INVENTORY_KEY_PREFIX = "product:inventory:";
    
    // Redis Lua脚本：原子性地减少库存
    private static final String LUA_DECREASE_INVENTORY =
            "local stock = redis.call('get', KEYS[1]); " +
            "if (stock and tonumber(stock) >= tonumber(ARGV[1])) then " +
            "    redis.call('decrby', KEYS[1], ARGV[1]); " +
            "    return 1; " +
            "else " +
            "    return 0; " +
            "end";

    // Redis Lua脚本：原子性地增加库存
    private static final String LUA_INCREASE_INVENTORY =
            "redis.call('incrby', KEYS[1], ARGV[1]); " +
            "return 1;";

    @Override
    public boolean tryReserveInventory(String itemId, int quantity) {
        String inventoryKey = INVENTORY_KEY_PREFIX + itemId;
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(LUA_DECREASE_INVENTORY, Long.class);
        Long result = redisTemplate.execute(redisScript, Collections.singletonList(inventoryKey), String.valueOf(quantity));
        
        boolean success = result != null && result == 1;
        if (success) {
            log.info("Successfully reserved inventory for item: {}, quantity: {}", itemId, quantity);
        } else {
            log.warn("Failed to reserve inventory for item: {}, quantity: {}", itemId, quantity);
        }
        
        return success;
    }

    @Override
    public void rollbackInventory(String itemId, int quantity) {
        String inventoryKey = INVENTORY_KEY_PREFIX + itemId;
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(LUA_INCREASE_INVENTORY, Long.class);
        redisTemplate.execute(redisScript, Collections.singletonList(inventoryKey), String.valueOf(quantity));
        log.info("Rolled back inventory for item: {}, quantity: {}", itemId, quantity);
    }
}
