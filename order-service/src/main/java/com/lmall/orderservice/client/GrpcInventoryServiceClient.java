package com.lmall.orderservice.client;

import inventory.InventoryServiceGrpc;
import inventory.Inventory.*;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;

/**
 * 基于gRPC的库存服务客户端实现
 * 通过gRPC调用inventory-service的API
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcInventoryServiceClient implements InventoryServiceClient {

    @GrpcClient("inventory-service")
    private InventoryServiceGrpc.InventoryServiceBlockingStub inventoryServiceStub;

    @Override
    public boolean checkInventory(String itemId, int quantity) {
        try {
            CheckInventoryRequest request = CheckInventoryRequest.newBuilder()
                    .setItemId(itemId)
                    .setQuantity(quantity)
                    .build();
            
            CheckInventoryResponse response = inventoryServiceStub.checkInventory(request);
            
            log.info("Inventory check for item: {}, required: {}, available: {}, result: {}", 
                    itemId, quantity, response.getCurrentStock(), response.getAvailable());
            
            return response.getAvailable();
        } catch (StatusRuntimeException e) {
            log.error("Failed to check inventory for item: {}", itemId, e);
            return false;
        }
    }
    
    @Override
    public boolean tryReserveInventory(String itemId, int quantity) {
        try {
            ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                    .setItemId(itemId)
                    .setQuantity(quantity)
                    .build();
            
            ReserveInventoryResponse response = inventoryServiceStub.tryReserveInventory(request);
            
            boolean success = response.getSuccess();
            if (success) {
                log.info("Successfully reserved inventory for item: {}, quantity: {}", itemId, quantity);
            } else {
                log.warn("Failed to reserve inventory for item: {}, quantity: {}, message: {}", 
                        itemId, quantity, response.getMessage());
            }
            
            return success;
        } catch (StatusRuntimeException e) {
            log.error("Failed to reserve inventory for item: {}", itemId, e);
            return false;
        }
    }
    
    @Override
    public boolean rollbackInventory(String itemId, int quantity) {
        try {
            ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
                    .setItemId(itemId)
                    .setQuantity(quantity)
                    .build();
            
            ReserveInventoryResponse response = inventoryServiceStub.rollbackInventory(request);
            
            boolean success = response.getSuccess();
            if (success) {
                log.info("Successfully rolled back inventory for item: {}, quantity: {}", itemId, quantity);
            } else {
                log.error("Failed to roll back inventory for item: {}, quantity: {}, message: {}", 
                        itemId, quantity, response.getMessage());
            }
            
            return success;
        } catch (StatusRuntimeException e) {
            log.error("Failed to roll back inventory for item: {}", itemId, e);
            return false;
        }
    }
    
    @Override
    public boolean confirmInventoryChange(String itemId, int quantity) {
        try {
            InventoryChangeRequest request = InventoryChangeRequest.newBuilder()
                    .setItemId(itemId)
                    .setQuantity(quantity)
                    .build();
            
            InventoryChangeResponse response = inventoryServiceStub.confirmInventoryChange(request);
            
            boolean success = response.getSuccess();
            if (success) {
                log.info("Successfully confirmed inventory change for item: {}, quantity: {}", itemId, quantity);
            } else {
                log.error("Failed to confirm inventory change for item: {}, quantity: {}, message: {}", 
                        itemId, quantity, response.getMessage());
            }
            
            return success;
        } catch (StatusRuntimeException e) {
            log.error("Failed to confirm inventory change for item: {}", itemId, e);
            return false;
        }
    }
    
    @Override
    public boolean prewarmInventory(String itemId) {
        try {
            PrewarmInventoryRequest request = PrewarmInventoryRequest.newBuilder()
                    .setItemId(itemId)
                    .build();
            
            PrewarmInventoryResponse response = inventoryServiceStub.prewarmInventory(request);
            
            boolean success = response.getSuccess();
            if (success) {
                log.info("Successfully sent prewarm request for item: {}", itemId);
            } else {
                log.warn("Failed to send prewarm request for item: {}, message: {}", 
                        itemId, response.getMessage());
            }
            
            return success;
        } catch (StatusRuntimeException e) {
            log.error("Failed to send prewarm request for item: {}", itemId, e);
            return false;
        }
    }
    
    @Override
    public boolean updateInventory(String itemId, int quantity) {
        // 这个方法已被confirmInventoryChange替代，但为了向后兼容保留
        return confirmInventoryChange(itemId, quantity);
    }
}
