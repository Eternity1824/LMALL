package com.lmall.orderservice.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis实现的临时订单存储库
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisTempOrderRepository implements TempOrderRepository {

    private final StringRedisTemplate redisTemplate;
    
    private static final String TEMP_ORDER_KEY_PREFIX = "order:temp:";
    private static final int TEMP_ORDER_TTL = 30 * 60; // 30分钟过期

    @Override
    public void saveTempOrder(String orderId, String userId, String itemId, int quantity) {
        String key = TEMP_ORDER_KEY_PREFIX + orderId;
        Map<String, String> orderData = new HashMap<>();
        orderData.put("userId", userId);
        orderData.put("itemId", itemId);
        orderData.put("quantity", String.valueOf(quantity));
        
        redisTemplate.opsForHash().putAll(key, orderData);
        redisTemplate.expire(key, Duration.ofSeconds(TEMP_ORDER_TTL));
        log.info("Saved temporary order: {} with TTL: {} seconds", orderId, TEMP_ORDER_TTL);
    }

    @Override
    public Map<String, String> getTempOrder(String orderId) {
        String key = TEMP_ORDER_KEY_PREFIX + orderId;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        
        Map<String, String> result = new HashMap<>();
        entries.forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }

    @Override
    public void removeTempOrder(String orderId) {
        String key = TEMP_ORDER_KEY_PREFIX + orderId;
        redisTemplate.delete(key);
        log.info("Removed temporary order: {}", orderId);
    }
}
