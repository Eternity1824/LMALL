package com.lmall.inventoryservice.controller;

import com.lmall.inventoryservice.service.InventoryService;
import com.lmall.inventoryservice.service.InventoryPrewarmService;
import inventory.InventoryServiceGrpc;
import inventory.Inventory.*;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * gRPC服务实现类
 * 处理库存相关的RPC请求
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class InventoryGrpcServiceImpl extends InventoryServiceGrpc.InventoryServiceImplBase {

    private final InventoryService inventoryService;
    private final InventoryPrewarmService inventoryPrewarmService;

    @Override
    public void checkInventory(CheckInventoryRequest request, StreamObserver<CheckInventoryResponse> responseObserver) {
        log.info("Received checkInventory request for item: {}, quantity: {}",
                request.getItemId(), request.getQuantity());
        
        try {
            boolean isAvailable = inventoryService.checkInventory(request.getItemId(), request.getQuantity());
            int currentStock = inventoryService.getCurrentStock(request.getItemId());
            
            CheckInventoryResponse response = CheckInventoryResponse.newBuilder()
                    .setAvailable(isAvailable)
                    .setCurrentStock(currentStock)
                    .setMessage(isAvailable ? "Inventory available" : "Insufficient inventory")
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error checking inventory for item: {}", request.getItemId(), e);
            
            CheckInventoryResponse response = CheckInventoryResponse.newBuilder()
                    .setAvailable(false)
                    .setCurrentStock(0)
                    .setMessage("Error checking inventory: " + e.getMessage())
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void tryReserveInventory(ReserveInventoryRequest request, StreamObserver<ReserveInventoryResponse> responseObserver) {
        log.info("Received tryReserveInventory request for item: {}, quantity: {}",
                request.getItemId(), request.getQuantity());
        
        try {
            boolean success = inventoryService.tryReserveInventory(request.getItemId(), request.getQuantity());
            
            ReserveInventoryResponse response = ReserveInventoryResponse.newBuilder()
                    .setSuccess(success)
                    .setMessage(success ? "Inventory reserved successfully" : "Failed to reserve inventory")
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error reserving inventory for item: {}", request.getItemId(), e);
            
            ReserveInventoryResponse response = ReserveInventoryResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Error reserving inventory: " + e.getMessage())
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void rollbackInventory(ReserveInventoryRequest request, StreamObserver<ReserveInventoryResponse> responseObserver) {
        log.info("Received rollbackInventory request for item: {}, quantity: {}",
                request.getItemId(), request.getQuantity());
        
        try {
            boolean success = inventoryService.rollbackInventory(request.getItemId(), request.getQuantity());
            
            ReserveInventoryResponse response = ReserveInventoryResponse.newBuilder()
                    .setSuccess(success)
                    .setMessage(success ? "Inventory rollback successful" : "Failed to rollback inventory")
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error rolling back inventory for item: {}", request.getItemId(), e);
            
            ReserveInventoryResponse response = ReserveInventoryResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Error rolling back inventory: " + e.getMessage())
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void confirmInventoryChange(InventoryChangeRequest request, StreamObserver<InventoryChangeResponse> responseObserver) {
        log.info("Received confirmInventoryChange request for item: {}, quantity: {}",
                request.getItemId(), request.getQuantity());
        
        try {
            boolean success = inventoryService.updateInventory(request.getItemId(), request.getQuantity());
            
            InventoryChangeResponse response = InventoryChangeResponse.newBuilder()
                    .setSuccess(success)
                    .setMessage(success ? "Inventory updated successfully" : "Failed to update inventory")
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error updating inventory for item: {}", request.getItemId(), e);
            
            InventoryChangeResponse response = InventoryChangeResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Error updating inventory: " + e.getMessage())
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void prewarmInventory(PrewarmInventoryRequest request, StreamObserver<PrewarmInventoryResponse> responseObserver) {
        String itemId = request.getItemId();
        log.info("Received prewarmInventory request for item: {}", itemId.isEmpty() ? "ALL" : itemId);
        
        try {
            boolean success;
            if (itemId.isEmpty()) {
                // 预热所有商品
                success = inventoryPrewarmService.prewarmAllInventory();
            } else {
                // 预热指定商品
                success = inventoryPrewarmService.prewarmInventory(itemId);
            }
            
            PrewarmInventoryResponse response = PrewarmInventoryResponse.newBuilder()
                    .setSuccess(success)
                    .setMessage(success ? "Inventory prewarm initiated successfully" : "Failed to initiate inventory prewarm")
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error prewarming inventory for item: {}", itemId.isEmpty() ? "ALL" : itemId, e);
            
            PrewarmInventoryResponse response = PrewarmInventoryResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Error prewarming inventory: " + e.getMessage())
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
