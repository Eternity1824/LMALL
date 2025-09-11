# 选择一个轻量级 JDK 镜像
FROM eclipse-temurin:17-jdk-alpine

# 创建工作目录
WORKDIR /app

# 复制 jar 包（你需要先用 Maven 打包好）
COPY target/order-service.jar app.jar

# 启动服务
ENTRYPOINT ["java", "-jar", "app.jar"]