package com.lmall.mqservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ配置类
 */
@Configuration
public class RabbitMQConfig {

    // 交换机名称
    public static final String ORDER_EXCHANGE = "order.exchange";
    public static final String INVENTORY_EXCHANGE = "inventory.exchange";
    
    // 队列名称
    public static final String ORDER_CREATION_QUEUE = "order.creation.queue";
    public static final String ORDER_STATUS_UPDATE_QUEUE = "order.status.update.queue";
    public static final String INVENTORY_UPDATE_QUEUE = "inventory.update.queue";
    public static final String INVENTORY_PREWARM_QUEUE = "inventory.prewarm.queue";
    
    // 路由键
    public static final String ORDER_CREATION_ROUTING_KEY = "order.create";
    public static final String ORDER_STATUS_UPDATE_ROUTING_KEY = "order.status.update";
    public static final String INVENTORY_UPDATE_ROUTING_KEY = "inventory.update";
    public static final String INVENTORY_PREWARM_ROUTING_KEY = "inventory.prewarm";
    
    // 声明交换机
    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE);
    }
    
    @Bean
    public DirectExchange inventoryExchange() {
        return new DirectExchange(INVENTORY_EXCHANGE);
    }
    
    // 声明队列
    @Bean
    public Queue orderCreationQueue() {
        return QueueBuilder.durable(ORDER_CREATION_QUEUE).build();
    }
    
    @Bean
    public Queue orderStatusUpdateQueue() {
        return QueueBuilder.durable(ORDER_STATUS_UPDATE_QUEUE).build();
    }
    
    @Bean
    public Queue inventoryUpdateQueue() {
        return QueueBuilder.durable(INVENTORY_UPDATE_QUEUE).build();
    }
    
    @Bean
    public Queue inventoryPrewarmQueue() {
        return QueueBuilder.durable(INVENTORY_PREWARM_QUEUE).build();
    }
    
    // 绑定队列到交换机
    @Bean
    public Binding orderCreationBinding() {
        return BindingBuilder.bind(orderCreationQueue())
                .to(orderExchange())
                .with(ORDER_CREATION_ROUTING_KEY);
    }
    
    @Bean
    public Binding orderStatusUpdateBinding() {
        return BindingBuilder.bind(orderStatusUpdateQueue())
                .to(orderExchange())
                .with(ORDER_STATUS_UPDATE_ROUTING_KEY);
    }
    
    @Bean
    public Binding inventoryUpdateBinding() {
        return BindingBuilder.bind(inventoryUpdateQueue())
                .to(inventoryExchange())
                .with(INVENTORY_UPDATE_ROUTING_KEY);
    }
    
    @Bean
    public Binding inventoryPrewarmBinding() {
        return BindingBuilder.bind(inventoryPrewarmQueue())
                .to(inventoryExchange())
                .with(INVENTORY_PREWARM_ROUTING_KEY);
    }
    
    // 配置RabbitTemplate
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());
        return rabbitTemplate;
    }
}
