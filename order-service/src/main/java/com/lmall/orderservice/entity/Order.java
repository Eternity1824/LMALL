package com.lmall.orderservice.entity;

import com.lmall.orderservice.enums.OrderStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private String orderId;
    private String userId;
    private String itemId;
    private int quantity;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    private LocalDateTime createdAt;
}
