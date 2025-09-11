package com.lmall.mqservice.repository;

import com.lmall.mqservice.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 库存数据访问接口
 */
@Repository
public interface InventoryRepository extends JpaRepository<Inventory, String> {
}
