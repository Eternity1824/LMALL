package com.lmall.orderservice.config;

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

    @Value("${rabbitmq.exchanges.order}")
    private String orderExchange;
    
    @Value("${rabbitmq.queues.order-creation}")
    private String orderCreationQueue;
    
    @Value("${rabbitmq.queues.order-status-update}")
    private String orderStatusUpdateQueue;
    
    @Value("${rabbitmq.routing-keys.order-creation}")
    private String orderCreationRoutingKey;
    
    @Value("${rabbitmq.routing-keys.order-status-update}")
    private String orderStatusUpdateRoutingKey;
    
    // 声明交换机
    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(orderExchange);
    }
    
    // 声明队列
    @Bean
    public Queue orderCreationQueue() {
        return QueueBuilder.durable(orderCreationQueue).build();
    }
    
    @Bean
    public Queue orderStatusUpdateQueue() {
        return QueueBuilder.durable(orderStatusUpdateQueue).build();
    }
    
    // 绑定队列到交换机
    @Bean
    public Binding orderCreationBinding() {
        return BindingBuilder.bind(orderCreationQueue())
                .to(orderExchange())
                .with(orderCreationRoutingKey);
    }
    
    @Bean
    public Binding orderStatusUpdateBinding() {
        return BindingBuilder.bind(orderStatusUpdateQueue())
                .to(orderExchange())
                .with(orderStatusUpdateRoutingKey);
    }
    
    // 配置RabbitTemplate
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());
        return rabbitTemplate;
    }
}
