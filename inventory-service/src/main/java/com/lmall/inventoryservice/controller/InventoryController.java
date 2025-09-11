package com.lmall.inventoryservice.controller;

import com.lmall.inventoryservice.entity.Inventory;
import com.lmall.inventoryservice.service.InventoryPrewarmService;
import com.lmall.inventoryservice.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 库存控制器
 * 提供库存查询和预热的REST API
 */
@Slf4j
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final InventoryPrewarmService prewarmService;

    /**
     * 获取商品库存
     * @param itemId 商品ID
     * @return 库存信息
     */
    @GetMapping("/{itemId}")
    public ResponseEntity<?> getInventory(@PathVariable String itemId) {
        return inventoryService.getInventory(itemId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * 获取商品可用库存（Redis）
     * @param itemId 商品ID
     * @return 可用库存数量
     */
    @GetMapping("/available/{itemId}")
    public ResponseEntity<?> getAvailableInventory(@PathVariable String itemId) {
        int available = inventoryService.getAvailableInventory(itemId);
        
        if (available < 0) {
            return ResponseEntity.notFound().build();
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("itemId", itemId);
        response.put("available", available);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 预热所有商品库存到Redis
     * @return 预热结果
     */
    @PostMapping("/prewarm")
    public ResponseEntity<?> prewarmAllInventory() {
        prewarmService.prewarmInventory();
        
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Inventory prewarm process started");
        response.put("status", "success");
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 为秒杀活动预热特定商品库存
     * @param itemId 商品ID
     * @return 预热结果
     */
    @PostMapping("/prewarm/{itemId}")
    public ResponseEntity<?> prewarmItemForSeckill(@PathVariable String itemId) {
        boolean success = prewarmService.prewarmItemForSeckill(itemId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("itemId", itemId);
        
        if (success) {
            response.put("message", "Item successfully prewarmed for seckill");
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } else {
            response.put("message", "Failed to prewarm item, item not found");
            response.put("status", "error");
            return ResponseEntity.badRequest().body(response);
        }
    }
}
