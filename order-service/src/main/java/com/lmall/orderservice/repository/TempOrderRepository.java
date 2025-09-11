package com.lmall.orderservice.repository;

import java.util.Map;

/**
 * 临时订单存储库接口
 * 用于存储订单的中间状态，支持后续的确认或取消操作
 */
public interface TempOrderRepository {
    
    /**
     * 保存临时订单
     * @param orderId 订单ID
     * @param userId 用户ID
     * @param itemId 商品ID
     * @param quantity 数量
     */
    void saveTempOrder(String orderId, String userId, String itemId, int quantity);
    
    /**
     * 获取临时订单
     * @param orderId 订单ID
     * @return 订单数据，如果不存在则返回null
     */
    Map<String, String> getTempOrder(String orderId);
    
    /**
     * 删除临时订单
     * @param orderId 订单ID
     */
    void removeTempOrder(String orderId);
}
