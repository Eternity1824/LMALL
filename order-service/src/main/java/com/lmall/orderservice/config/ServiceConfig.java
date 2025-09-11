package com.lmall.orderservice.config;

import com.lmall.orderservice.client.GrpcInventoryServiceClient;
import com.lmall.orderservice.client.InventoryServiceClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 服务配置类
 */
@Configuration
public class ServiceConfig {

    /**
     * 配置主要的库存服务客户端实现
     * 使用gRPC实现替代HTTP实现
     */
    @Bean
    @Primary
    public InventoryServiceClient inventoryServiceClient(GrpcInventoryServiceClient grpcInventoryServiceClient) {
        return grpcInventoryServiceClient;
    }
}
