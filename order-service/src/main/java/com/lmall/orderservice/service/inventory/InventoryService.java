package com.lmall.orderservice.service.inventory;

/**
 * 库存服务接口
 * 负责处理商品库存的预留和释放
 */
public interface InventoryService {
    
    /**
     * 尝试预留库存
     * @param itemId 商品ID
     * @param quantity 数量
     * @return 是否预留成功
     */
    boolean tryReserveInventory(String itemId, int quantity);
    
    /**
     * 回滚库存（释放之前预留的库存）
     * @param itemId 商品ID
     * @param quantity 数量
     */
    void rollbackInventory(String itemId, int quantity);
}
