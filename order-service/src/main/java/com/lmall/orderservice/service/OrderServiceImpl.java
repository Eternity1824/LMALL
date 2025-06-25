package com.lmall.orderservice.service;

import com.lmall.orderservice.entity.Order;
import com.lmall.orderservice.enums.OrderStatus;
import com.lmall.orderservice.repository.OrderRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import order.Order.OrderRequest;
import order.Order.OrderResponse;
import order.OrderServiceGrpc;

import java.time.LocalDateTime;
import java.time.Duration;

@GrpcService
public class OrderServiceImpl extends OrderServiceGrpc.OrderServiceImplBase {

    private final OrderRepository orderRepo;
    private final SnowflakeIdGenerator idGenerator;
    private final StringRedisTemplate redisTemplate;

    public OrderServiceImpl(OrderRepository orderRepo, SnowflakeIdGenerator idGenerator, StringRedisTemplate redisTemplate) {
        this.orderRepo = orderRepo;
        this.idGenerator = idGenerator;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void createOrder(OrderRequest request, StreamObserver<OrderResponse> responseObserver) {
        // 从请求中获取数据
        String userId = request.getUserId();
        String itemId = request.getItemId();
        int quantity = request.getQuantity();

        // 检查是否已下单（Redis幂等缓存）
        String placedKey = "order:placed:" + userId + ":" + itemId;
        String existingOrderId = redisTemplate.opsForValue().get(placedKey);
        if (existingOrderId != null) {
            OrderResponse response = OrderResponse.newBuilder()
                    .setOrderId(existingOrderId)
                    .setMessage("Order already placed")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }

        // 创建订单
        String orderId = createOrderInDatabase(userId, itemId, quantity);

        // 设置已下单标记，存储实际orderId且设置1小时TTL
        redisTemplate.opsForValue().set(placedKey, orderId, Duration.ofHours(1));

        // 构建响应
        OrderResponse response = OrderResponse.newBuilder()
                .setOrderId(orderId)
                .setMessage("Order created successfully")
                .build();

        // 发送响应
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private String createOrderInDatabase(String userId, String itemId, int quantity) {
        String orderId = String.valueOf(idGenerator.nextId());

        Order order = new Order();
        order.setOrderId(orderId);
        order.setUserId(userId);
        order.setItemId(itemId);
        order.setQuantity(quantity);
        order.setStatus(OrderStatus.PENDING);
        order.setCreatedAt(LocalDateTime.now());

        orderRepo.save(order);
        return orderId;
    }
}
