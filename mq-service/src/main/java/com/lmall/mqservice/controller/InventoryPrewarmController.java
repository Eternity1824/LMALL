package com.lmall.mqservice.controller;

import com.lmall.mqservice.service.InventoryPrewarmMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存预热控制器
 * 提供触发库存预热的REST API
 */
@Slf4j
@RestController
@RequestMapping("/api/prewarm")
@RequiredArgsConstructor
public class InventoryPrewarmController {

    private final InventoryPrewarmMessageService prewarmMessageService;

    /**
     * 触发单个商品的库存预热
     * @param itemId 商品ID
     * @return 预热结果
     */
    @PostMapping("/{itemId}")
    public ResponseEntity<?> prewarmItem(@PathVariable String itemId) {
        boolean success = prewarmMessageService.sendInventoryPrewarmMessage(itemId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("itemId", itemId);
        
        if (success) {
            response.put("message", "Inventory prewarm message sent successfully");
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } else {
            response.put("message", "Failed to send inventory prewarm message");
            response.put("status", "error");
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    /**
     * 批量触发商品库存预热
     * @param request 包含商品ID列表的请求体
     * @return 预热结果
     */
    @PostMapping("/batch")
    public ResponseEntity<?> prewarmBatch(@RequestBody Map<String, List<String>> request) {
        List<String> itemIds = request.get("itemIds");
        
        if (itemIds == null || itemIds.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("message", "No item IDs provided");
            errorResponse.put("status", "error");
            return ResponseEntity.badRequest().body(errorResponse);
        }
        
        int successCount = prewarmMessageService.sendBatchInventoryPrewarmMessages(itemIds);
        
        Map<String, Object> response = new HashMap<>();
        response.put("totalItems", itemIds.size());
        response.put("successCount", successCount);
        response.put("message", "Batch inventory prewarm messages sent");
        response.put("status", "success");
        
        return ResponseEntity.ok(response);
    }
}
