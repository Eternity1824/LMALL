FROM openjdk:11-jdk-slim

WORKDIR /app

COPY target/mq-service-1.0.0.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
