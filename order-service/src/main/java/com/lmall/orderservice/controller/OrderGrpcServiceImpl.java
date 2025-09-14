package com.lmall.orderservice.controller;

import com.lmall.orderservice.entity.Order;
import com.lmall.orderservice.manager.OrderStateManager;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import order.*;

/**
 * gRPC服务实现类
 * 处理订单相关的RPC请求
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class OrderGrpcServiceImpl extends OrderServiceGrpc.OrderServiceImplBase {

    private final OrderStateManager orderStateManager;

    @Override
    public void createOrder(order.Order.CreateOrderRequest request, StreamObserver<CreateOrderResponse> responseObserver) {
        log.info("Received createOrder request for user: {}, item: {}, quantity: {}",
                request.getUserId(), request.getItemId(), request.getQuantity());
        
        try {
            // 调用OrderStateManager的tryOrder方法（Try阶段）
            String orderId = orderStateManager.tryOrder(
                    request.getUserId(),
                    request.getItemId(),
                    request.getQuantity()
            );
            
            // 构建成功响应
            CreateOrderResponse response = CreateOrderResponse.newBuilder()
                    .setOrderId(orderId)
                    .setMessage("Order created successfully")
                    .build();
            
            log.info("Successfully created order: {}", orderId);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error creating order", e);
            
            // 构建失败响应
            CreateOrderResponse response = CreateOrderResponse.newBuilder()
                    .setOrderId("")
                    .setMessage("Failed to create order: " + e.getMessage())
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
    
    @Override
    public void confirmOrder(ConfirmOrderRequest request, StreamObserver<ConfirmOrderResponse> responseObserver) {
        log.info("Received confirmOrder request for order: {}, payment: {}",
                request.getOrderId(), request.getPaymentId());
        
        try {
            // 调用OrderStateManager的confirmOrder方法（Confirm阶段）
            orderStateManager.confirmOrder(request.getOrderId(), request.getPaymentId());
            
            // 构建成功响应
            order.Order.ConfirmOrderResponse response = order.Order.ConfirmOrderResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Order confirmed successfully")
                    .build();
            
            log.info("Successfully confirmed order: {}", request.getOrderId());
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error confirming order: {}", request.getOrderId(), e);
            
            // 构建失败响应
            ConfirmOrderResponse response = ConfirmOrderResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to confirm order: " + e.getMessage())
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
    
    @Override
    public void cancelOrder(CancelOrderRequest request, StreamObserver<CancelOrderResponse> responseObserver) {
        log.info("Received cancelOrder request for order: {}, reason: {}",
                request.getOrderId(), request.getReason());
        
        try {
            // 调用OrderStateManager的cancelOrder方法（Cancel阶段）
            orderStateManager.cancelOrder(request.getOrderId(), request.getReason());
            
            // 构建成功响应
            order.Order.CancelOrderResponse response = CancelOrderResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Order cancelled successfully")
                    .build();
            
            log.info("Successfully cancelled order: {}", request.getOrderId());
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error cancelling order: {}", request.getOrderId(), e);
            
            // 构建失败响应
            CancelOrderResponse response = CancelOrderResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to cancel order: " + e.getMessage())
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void getOrder(GetOrderRequest request, StreamObserver<GetOrderResponse> responseObserver) {
        log.info("Received getOrder request for order: {}", request.getOrderId());
        
        try {
            // 调用OrderStateManager的getOrder方法
            Order order = orderStateManager.getOrder(request.getOrderId());
            
            if (order != null) {
                // 构建成功响应
                GetOrderResponse response = GetOrderResponse.newBuilder()
                        .setOrderId(order.getOrderId())
                        .setUserId(order.getUserId())
                        .setItemId(order.getItemId())
                        .setQuantity(order.getQuantity())
                        .setStatus(order.getStatus().name())
                        .setSuccess(true)
                        .setMessage("Order retrieved successfully")
                        .build();
                
                log.info("Successfully retrieved order: {}", order.getOrderId());
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } else {
                // 订单不存在
                GetOrderResponse response = GetOrderResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Order not found")
                        .build();
                
                log.warn("Order not found: {}", request.getOrderId());
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            }
        } catch (Exception e) {
            log.error("Error retrieving order: {}", request.getOrderId(), e);
            
            // 构建失败响应
            GetOrderResponse response = GetOrderResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to retrieve order: " + e.getMessage())
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
