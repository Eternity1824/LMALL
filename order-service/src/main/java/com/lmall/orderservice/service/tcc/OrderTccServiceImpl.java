package com.lmall.orderservice.service.tcc;

import com.lmall.orderservice.entity.Order;
import com.lmall.orderservice.enums.OrderStatus;
import com.lmall.orderservice.repository.TempOrderRepository;
import com.lmall.orderservice.service.OrderService;
import com.lmall.orderservice.service.inventory.InventoryService;
import com.lmall.orderservice.service.message.MessageService;
import com.lmall.orderservice.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 订单TCC服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTccServiceImpl implements OrderTccService {

    private final OrderService orderService;
    private final InventoryService inventoryService;
    private final TempOrderRepository tempOrderRepository;
    private final MessageService messageService;
    private final SnowflakeIdGenerator idGenerator;

    @Override
    public String tryOrder(String userId, String itemId, int quantity) {
        // 1. 生成订单ID
        String orderId = String.valueOf(idGenerator.nextId());
        log.info("Trying to create order: {}, user: {}, item: {}, quantity: {}", orderId, userId, itemId, quantity);
        
        // 2. 尝试预留库存
        boolean inventoryReserved = inventoryService.tryReserveInventory(itemId, quantity);
        if (!inventoryReserved) {
            log.warn("Failed to reserve inventory for item: {}, quantity: {}", itemId, quantity);
            throw new RuntimeException("Insufficient inventory");
        }
        
        try {
            // 3. 保存临时订单
            tempOrderRepository.saveTempOrder(orderId, userId, itemId, quantity);
            log.info("Successfully created temporary order: {}", orderId);
            
            return orderId;
        } catch (Exception e) {
            // 4. 如果保存临时订单失败，回滚库存
            log.error("Failed to save temporary order, rolling back inventory for item: {}", itemId, e);
            inventoryService.rollbackInventory(itemId, quantity);
            throw new RuntimeException("Failed to create order, please try again.", e);
        }
    }

    @Override
    public void confirmOrder(String orderId, String paymentId) {
        // 1. 获取临时订单
        Map<String, String> tempOrderData = tempOrderRepository.getTempOrder(orderId);
        if (tempOrderData == null || tempOrderData.isEmpty()) {
            log.warn("Confirm failed, temporary order not found: {}", orderId);
            throw new RuntimeException("Order not found or has expired");
        }
        
        String userId = tempOrderData.get("userId");
        String itemId = tempOrderData.get("itemId");
        int quantity = Integer.parseInt(tempOrderData.get("quantity"));
        
        try {
            // 2. 创建订单对象
            Order order = new Order();
            order.setOrderId(orderId);
            order.setUserId(userId);
            order.setItemId(itemId);
            order.setQuantity(quantity);
            order.setStatus(OrderStatus.CONFIRMED);
            order.setCreatedAt(LocalDateTime.now());
            
            // 3. 发送订单创建消息到MQ，由消息消费者异步持久化到数据库
            messageService.sendOrderCreationMessage(order);
            log.info("Sent order creation message to message queue for order: {}", orderId);
            
            // 4. 删除临时订单
            tempOrderRepository.removeTempOrder(orderId);
            
            // 5. 记录支付ID（如果有）
            if (paymentId != null && !paymentId.isEmpty()) {
                // 这里可以添加保存支付ID的逻辑，例如发送支付关联消息
                log.info("Payment ID {} associated with order {}", paymentId, orderId);
            }
        } catch (Exception e) {
            log.error("Failed to confirm order: {}", orderId, e);
            // 注意：这里我们不回滚库存，因为订单仍然有效，只是持久化失败
            // 可以通过重试机制或定时任务来处理这种情况
            throw new RuntimeException("Failed to confirm order, please try again.", e);
        }
    }

    @Override
    public void cancelOrder(String orderId, String reason) {
        // 1. 获取临时订单
        Map<String, String> tempOrderData = tempOrderRepository.getTempOrder(orderId);
        if (tempOrderData == null || tempOrderData.isEmpty()) {
            // 订单可能已经确认或已经取消
            log.warn("Cancel failed, temporary order not found: {}", orderId);
            return;
        }
        
        String userId = tempOrderData.get("userId");
        String itemId = tempOrderData.get("itemId");
        int quantity = Integer.parseInt(tempOrderData.get("quantity"));
        
        // 2. 回滚库存
        inventoryService.rollbackInventory(itemId, quantity);
        
        // 3. 删除临时订单
        tempOrderRepository.removeTempOrder(orderId);
        
        // 4. 可选：记录取消信息到数据库（通过消息队列异步处理）
        try {
            Order order = new Order();
            order.setOrderId(orderId);
            order.setUserId(userId);
            order.setItemId(itemId);
            order.setQuantity(quantity);
            order.setStatus(OrderStatus.CANCELLED);
            order.setCreatedAt(LocalDateTime.now());
            
            messageService.sendOrderCreationMessage(order);
            
            if (reason != null && !reason.isEmpty()) {
                // 发送取消原因（可以作为额外的消息或者状态更新）
                messageService.sendOrderStatusUpdateMessage(orderId, OrderStatus.CANCELLED);
                log.info("Order {} cancelled with reason: {}", orderId, reason);
            }
        } catch (Exception e) {
            // 非关键错误，库存已经回滚
            log.warn("Failed to record order cancellation in message queue: {}", orderId, e);
        }
        
        log.info("Order {} cancelled, inventory rolled back for item {}", orderId, itemId);
    }
    
    @Override
    public Order getOrder(String orderId) {
        // 1. 首先检查临时订单
        Map<String, String> tempOrderData = tempOrderRepository.getTempOrder(orderId);
        if (tempOrderData != null && !tempOrderData.isEmpty()) {
            // 将临时订单数据转换为Order对象
            Order order = new Order();
            order.setOrderId(orderId);
            order.setUserId(tempOrderData.get("userId"));
            order.setItemId(tempOrderData.get("itemId"));
            order.setQuantity(Integer.parseInt(tempOrderData.get("quantity")));
            order.setStatus(OrderStatus.PENDING); // 临时订单状态为PENDING
            log.info("Retrieved temporary order: {}", orderId);
            return order;
        }
        
        // 2. 如果临时订单不存在，则查询数据库
        log.info("Temporary order not found, querying database for order: {}", orderId);
        return orderService.getOrder(orderId);
    }
}
