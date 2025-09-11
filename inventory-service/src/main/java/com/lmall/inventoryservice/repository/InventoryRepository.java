package com.lmall.inventoryservice.repository;

import com.lmall.inventoryservice.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * 库存数据访问接口
 */
@Repository
public interface InventoryRepository extends JpaRepository<Inventory, String> {

    /**
     * 更新商品库存
     * @param itemId 商品ID
     * @param quantity 数量变化
     * @return 影响的行数
     */
    @Modifying
    @Query("UPDATE Inventory i SET i.quantity = i.quantity + :quantity, " +
           "i.available = i.quantity - i.reserved WHERE i.itemId = :itemId")
    int updateInventoryQuantity(String itemId, int quantity);
    
    /**
     * 更新商品预留库存
     * @param itemId 商品ID
     * @param reserved 预留数量变化
     * @return 影响的行数
     */
    @Modifying
    @Query("UPDATE Inventory i SET i.reserved = i.reserved + :reserved, " +
           "i.available = i.quantity - i.reserved WHERE i.itemId = :itemId")
    int updateReservedInventory(String itemId, int reserved);
}
