package com.lmall.orderservice.manager;

import com.lmall.orderservice.entity.Order;
import com.lmall.orderservice.service.tcc.OrderTccService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 订单状态管理器
 * 作为协调者，调用各个服务完成订单流程
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStateManager {

    private final OrderTccService orderTccService;

    /**
     * 尝试创建订单（TCC - Try阶段）
     * 委托给OrderTccService处理
     * 
     * @param userId 用户ID
     * @param itemId 商品ID
     * @param quantity 数量
     * @return 订单ID
     */
    public String tryOrder(String userId, String itemId, int quantity) {
        return orderTccService.tryOrder(userId, itemId, quantity);
    }

    /**
     * 确认订单（TCC - Confirm阶段）
     * 委托给OrderTccService处理
     * 
     * @param orderId 订单ID
     * @param paymentId 支付ID（可选）
     */
    public void confirmOrder(String orderId, String paymentId) {
        orderTccService.confirmOrder(orderId, paymentId);
    }

    /**
     * 确认订单（不带支付ID的重载方法）
     * 
     * @param orderId 订单ID
     */
    public void confirmOrder(String orderId) {
        orderTccService.confirmOrder(orderId, null);
    }

    /**
     * 取消订单（TCC - Cancel阶段）
     * 委托给OrderTccService处理
     * 
     * @param orderId 订单ID
     * @param reason 取消原因（可选）
     */
    public void cancelOrder(String orderId, String reason) {
        orderTccService.cancelOrder(orderId, reason);
    }

    /**
     * 取消订单（不带原因的重载方法）
     * 
     * @param orderId 订单ID
     */
    public void cancelOrder(String orderId) {
        orderTccService.cancelOrder(orderId, null);
    }

    /**
     * 获取订单
     * 委托给OrderService处理
     * 
     * @param orderId 订单ID
     * @return 订单信息
     */
    public Order getOrder(String orderId) {
        // 这里可以添加更复杂的逻辑，例如先检查临时订单，再查询数据库
        // 但为了简化，我们直接从TccService获取订单状态
        try {
            return orderTccService.getOrder(orderId);
        } catch (Exception e) {
            log.error("Error getting order: {}", orderId, e);
            return null;
        }
    }
}
