package com.lmall.mqservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MQ服务应用程序入口
 * 专门负责消息队列的处理和数据库操作的调度
 */
@SpringBootApplication
public class MqServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MqServiceApplication.class, args);
    }
}
