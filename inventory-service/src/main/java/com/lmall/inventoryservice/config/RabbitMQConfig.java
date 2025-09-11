package com.lmall.inventoryservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ配置类
 */
@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchanges.inventory}")
    private String inventoryExchange;
    
    @Value("${rabbitmq.queues.inventory-update}")
    private String inventoryUpdateQueue;
    
    @Value("${rabbitmq.queues.inventory-prewarm}")
    private String inventoryPrewarmQueue;
    
    @Value("${rabbitmq.routing-keys.inventory-update}")
    private String inventoryUpdateRoutingKey;
    
    @Value("${rabbitmq.routing-keys.inventory-prewarm}")
    private String inventoryPrewarmRoutingKey;
    
    // 声明交换机
    @Bean
    public DirectExchange inventoryExchange() {
        return new DirectExchange(inventoryExchange);
    }
    
    // 声明队列
    @Bean
    public Queue inventoryUpdateQueue() {
        return QueueBuilder.durable(inventoryUpdateQueue).build();
    }
    
    @Bean
    public Queue inventoryPrewarmQueue() {
        return QueueBuilder.durable(inventoryPrewarmQueue).build();
    }
    
    // 绑定队列到交换机
    @Bean
    public Binding inventoryUpdateBinding() {
        return BindingBuilder.bind(inventoryUpdateQueue())
                .to(inventoryExchange())
                .with(inventoryUpdateRoutingKey);
    }
    
    @Bean
    public Binding inventoryPrewarmBinding() {
        return BindingBuilder.bind(inventoryPrewarmQueue())
                .to(inventoryExchange())
                .with(inventoryPrewarmRoutingKey);
    }
    
    // 配置RabbitTemplate
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());
        return rabbitTemplate;
    }
}
