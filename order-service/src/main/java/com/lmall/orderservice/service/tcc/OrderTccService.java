package com.lmall.orderservice.service.tcc;

import com.lmall.orderservice.entity.Order;

/**
 * 订单TCC服务接口
 * 实现TCC（Try-Confirm-Cancel）模式的订单处理
 */
public interface OrderTccService {
    
    /**
     * Try阶段：尝试创建订单
     * 预留资源（库存），并创建临时订单
     * 
     * @param userId 用户ID
     * @param itemId 商品ID
     * @param quantity 数量
     * @return 订单ID
     */
    String tryOrder(String userId, String itemId, int quantity);
    
    /**
     * Confirm阶段：确认订单
     * 将临时订单转为正式订单
     * 
     * @param orderId 订单ID
     * @param paymentId 支付ID（可选）
     */
    void confirmOrder(String orderId, String paymentId);
    
    /**
     * Cancel阶段：取消订单
     * 释放预留的资源，删除临时订单
     * 
     * @param orderId 订单ID
     * @param reason 取消原因（可选）
     */
    void cancelOrder(String orderId, String reason);
    
    /**
     * 获取订单信息
     * 先检查临时订单，再查询数据库
     * 
     * @param orderId 订单ID
     * @return 订单对象
     */
    Order getOrder(String orderId);
}
