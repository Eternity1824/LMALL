# LMall 微服务商城系统

## 模块说明

- common：通用 DTO、工具类
- user-service：用户服务
- product-service：商品服务
- inventory-service：库存服务
- order-service：订单服务
- payment-service：支付服务（含 TCC）
- gateway-service：API 网关
- id-generator：分布式主键
- mq-service：消息队列封装模块

每个模块可独立构建部署，统一管理于本仓库。
