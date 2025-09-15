package com.lmall.orderservice.mapper;

import com.lmall.orderservice.dto.CreateOrderRequest;
import com.lmall.orderservice.dto.OrderResponse;
import com.lmall.orderservice.entity.Order;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * MapStruct Mapper for Order
 *
 * Benefits of MapStruct:
 * - Compile-time code generation (no reflection)
 * - Type-safe mapping
 * - Automatic null checks
 * - Performance: faster than manual mapping or reflection
 *
 * @Mapper(componentModel = "spring") creates a Spring bean
 */
@Mapper(componentModel = "spring")
public interface OrderMapper {

    /**
     * Map CreateOrderRequest to Order entity
     * @Mapping specifies custom field mappings
     * ignore = true for fields we'll set manually
     */
    @Mapping(target = "orderId", ignore = true)  // Generated separately
    @Mapping(target = "totalAmount", expression = "java(request.getPrice().multiply(new java.math.BigDecimal(request.getQuantity())))")
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "tccState", ignore = true)
    @Mapping(target = "tccTransactionId", ignore = true)
    @Mapping(target = "tryTimestamp", ignore = true)
    @Mapping(target = "confirmTimestamp", ignore = true)
    @Mapping(target = "paymentId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    Order toEntity(CreateOrderRequest request);

    /**
     * Map Order entity to OrderResponse DTO
     */
    OrderResponse toResponse(Order order);

    /**
     * Update existing entity from request
     * Useful for partial updates
     */
    @Mapping(target = "orderId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    void updateEntityFromRequest(CreateOrderRequest request, @MappingTarget Order order);
}