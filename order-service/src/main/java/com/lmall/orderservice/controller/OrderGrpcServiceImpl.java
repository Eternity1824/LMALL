package com.lmall.orderservice.controller;

import com.lmall.orderservice.service.OrderService;
import net.devh.boot.grpc.server.service.GrpcService;
import order.OrderServiceGrpc;

@GrpcService
public class OrderGrpcServiceImpl extends OrderServiceGrpc.OrderServiceImplBase {

    private final OrderService orderService;

    public OrderGrpcServiceImpl(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public void createOrder(order.Order.OrderRequest request, io.grpc.stub.StreamObserver<order.Order.OrderResponse> responseObserver) {
        String orderId = orderService.createOrder(
                request.getUserId(),
                request.getItemId(),
                request.getQuantity()
        );

        order.Order.OrderResponse response = order.Order.OrderResponse.newBuilder()
                .setOrderId(orderId)
                .setMessage("Order created successfully")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

}
