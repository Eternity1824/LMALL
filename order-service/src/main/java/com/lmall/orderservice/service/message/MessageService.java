package com.lmall.orderservice.service.message;

import com.lmall.orderservice.entity.Order;
import com.lmall.orderservice.enums.OrderStatus;

/**
 * 消息服务接口
 * 负责发送订单相关消息到消息队列
 */
public interface MessageService {
    
    /**
     * 发送订单创建消息
     * @param order 订单信息
     */
    void sendOrderCreationMessage(Order order);
    
    /**
     * 发送订单状态更新消息
     * @param orderId 订单ID
     * @param status 订单状态
     */
    void sendOrderStatusUpdateMessage(String orderId, OrderStatus status);
}
