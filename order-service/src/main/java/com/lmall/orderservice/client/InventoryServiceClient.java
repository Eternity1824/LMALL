package com.lmall.orderservice.client;

/**
 * 库存服务客户端接口
 * 用于与inventory-service进行通信
 */
public interface InventoryServiceClient {
    
    /**
     * 检查商品库存是否充足
     * @param itemId 商品ID
     * @param quantity 数量
     * @return 是否充足
     */
    boolean checkInventory(String itemId, int quantity);
    
    /**
     * 尝试预留库存
     * @param itemId 商品ID
     * @param quantity 数量
     * @return 是否预留成功
     */
    boolean tryReserveInventory(String itemId, int quantity);
    
    /**
     * 回滚库存预留
     * @param itemId 商品ID
     * @param quantity 数量
     * @return 是否回滚成功
     */
    boolean rollbackInventory(String itemId, int quantity);
    
    /**
     * 确认库存变更（发送异步消息）
     * @param itemId 商品ID
     * @param quantity 数量变化
     * @return 是否发送成功
     */
    boolean confirmInventoryChange(String itemId, int quantity);
    
    /**
     * 预热商品库存到Redis
     * @param itemId 商品ID
     * @return 是否预热成功
     */
    boolean prewarmInventory(String itemId);
    
    /**
     * 更新商品库存
     * @param itemId 商品ID
     * @param quantity 数量变化（正数为增加，负数为减少）
     * @return 是否更新成功
     */
    boolean updateInventory(String itemId, int quantity);
}
