# LMall Microservices E-Commerce Platform

**LMall** is a next-generation, cloud-native microservices platform for online retail. Built with Spring Boot, Maven multimodule architecture, and industry-proven design patterns, LMall delivers high scalability, resilience, and rapid feature delivery.

---

## 📐 Architecture Overview
                                   +-------------------+
                                   |   API Gateway     |
                                   | (Spring Cloud GW) |
                                   +---------+---------+
                                             |
      +-----------------+   +---------------+--------------+   +----------------+
      | user-service    |   | product-service                |   | inventory-svc  |
      | (Auth & Profile)|   | (Catalog & Pricing)            |   | (Stock & Lua)  |
      +--------+--------+   +---------------+--------------+   +--------+-------+
               |                          |                           |
               |                          |                           |
      +--------v--------+         +-------v--------+         +--------v--------+
      | order-service   | <-----> | payment-service| <-----> |  mq-service     |
      | (Order Workflow)|         | (TCC Payment)  |         | (RabbitMQ API)  |
      +-----------------+         +----------------+         +-----------------+
               |
               |
      +--------v--------+
      | id-generator    |
      | (Snowflake MS)  |
      +-----------------+
---

## 🔍 Module Breakdown

| Module               | Responsibility                                                     |
|----------------------|--------------------------------------------------------------------|
| **common**           | Shared DTOs, utility classes, exception handling, and config beans |
| **user-service**     | User registration, authentication (JWT), profile management        |
| **product-service**  | Product catalog, pricing, categories, search integration           |
| **inventory-service**| Real-time stock management (Redis Cluster + Lua scripting)         |
| **order-service**    | Order lifecycle: creation, validation, status tracking             |
| **payment-service**  | Distributed TCC payment orchestration                              |
| **mq-service**       | Unified RabbitMQ client wrapper (delay queues, DLQ, retries)       |
| **id-generator**     | Distributed ID generator (Snowflake algorithm)                     |
| **gateway-service**  | API Gateway with routing, load-balancing, security filters         |

---

## 🚀 Getting Started

### 1. Prerequisites

- Java 17
- Maven 3.8+
- Docker & Docker Compose

### 2. Clone & Build

```bash
git clone https://github.com/your-username/LMall.git
cd LMall
mvn clean install -DskipTests
````
3. Run All Services with Docker Compose

```bash
docker-compose up --build -d
```

4. Access API Gateway

Visit: http://localhost:8080/actuator/health
to verify all services are running.