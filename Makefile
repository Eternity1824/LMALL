# LMall Makefile for Development Tasks

.PHONY: help
help: ## Show this help message
	@echo 'Usage: make [target]'
	@echo ''
	@echo 'Available targets:'
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "  %-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# ============================================
# Docker Commands
# ============================================

.PHONY: up
up: ## Start all services with RabbitMQ (default)
	docker-compose up -d
	@echo "Services started. Access:"
	@echo "  - Gateway: http://localhost:8080"
	@echo "  - RabbitMQ: http://localhost:15672 (admin/admin)"
	@echo "  - PostgreSQL: localhost:5432 (postgres/postgres)"

.PHONY: up-aws
up-aws: ## Start all services with LocalStack
	docker-compose --profile aws up -d
	@echo "Waiting for LocalStack initialization..."
	@sleep 10
	@echo "Services started. Access:"
	@echo "  - Gateway: http://localhost:8080"
	@echo "  - LocalStack: http://localhost:4566"
	@echo "  - PostgreSQL: localhost:5432 (postgres/postgres)"

.PHONY: down
down: ## Stop all services
	docker-compose down
	docker-compose --profile aws down

.PHONY: clean
clean: ## Stop all services and remove volumes
	docker-compose down -v
	docker-compose --profile aws down -v

.PHONY: logs
logs: ## Show logs for all services
	docker-compose logs -f

.PHONY: logs-order
logs-order: ## Show logs for order service
	docker-compose logs -f order-service

.PHONY: ps
ps: ## Show running services
	docker-compose ps

# ============================================
# Build Commands
# ============================================

.PHONY: build
build: ## Build all services
	mvn clean package -DskipTests

.PHONY: build-docker
build-docker: ## Build Docker images
	docker-compose build

.PHONY: rebuild
rebuild: clean build build-docker up ## Clean, build, and restart everything

# ============================================
# Test Commands
# ============================================

.PHONY: test
test: ## Run unit tests
	mvn test

.PHONY: test-integration
test-integration: ## Run integration tests
	mvn verify -Pintegration-test

.PHONY: test-rabbitmq
test-rabbitmq: ## Run tests with RabbitMQ profile
	SPRING_PROFILES_ACTIVE=local-rabbitmq mvn test

.PHONY: test-aws
test-aws: ## Run tests with AWS LocalStack profile
	SPRING_PROFILES_ACTIVE=local-aws mvn test

.PHONY: test-both
test-both: ## Test both RabbitMQ and LocalStack
	./scripts/test-both-providers.sh

# ============================================
# Database Commands
# ============================================

.PHONY: db-init
db-init: ## Initialize database
	docker exec -i lmall-postgres psql -U postgres < scripts/init-db.sql

.PHONY: db-connect
db-connect: ## Connect to PostgreSQL
	docker exec -it lmall-postgres psql -U postgres -d lmall

.PHONY: db-reset
db-reset: ## Reset database
	docker exec lmall-postgres psql -U postgres -c "DROP DATABASE IF EXISTS lmall;"
	docker exec lmall-postgres psql -U postgres -c "CREATE DATABASE lmall;"
	$(MAKE) db-init

# ============================================
# RabbitMQ Commands
# ============================================

.PHONY: rabbitmq-status
rabbitmq-status: ## Check RabbitMQ status
	docker exec lmall-rabbitmq rabbitmqctl status

.PHONY: rabbitmq-queues
rabbitmq-queues: ## List RabbitMQ queues
	docker exec lmall-rabbitmq rabbitmqctl list_queues name messages consumers

.PHONY: rabbitmq-ui
rabbitmq-ui: ## Open RabbitMQ Management UI
	open http://localhost:15672

# ============================================
# LocalStack Commands
# ============================================

.PHONY: localstack-init
localstack-init: ## Initialize LocalStack resources
	docker exec lmall-localstack /etc/localstack/init/ready.d/init.sh

.PHONY: localstack-status
localstack-status: ## Check LocalStack resources
	@echo "SNS Topics:"
	@aws --endpoint-url=http://localhost:4566 sns list-topics --output table
	@echo "\nSQS Queues:"
	@aws --endpoint-url=http://localhost:4566 sqs list-queues --output table

.PHONY: localstack-reset
localstack-reset: ## Reset LocalStack
	docker-compose --profile aws down
	docker volume rm lmall_localstack-data 2>/dev/null || true
	docker-compose --profile aws up -d

# ============================================
# Development Commands
# ============================================

.PHONY: dev-order
dev-order: ## Run order service locally
	cd order-service && SPRING_PROFILES_ACTIVE=local-rabbitmq mvn spring-boot:run

.PHONY: dev-inventory
dev-inventory: ## Run inventory service locally
	cd inventory-service && SPRING_PROFILES_ACTIVE=local-rabbitmq mvn spring-boot:run

.PHONY: dev-payment
dev-payment: ## Run payment service locally
	cd payment-service && SPRING_PROFILES_ACTIVE=local-rabbitmq mvn spring-boot:run

.PHONY: dev-gateway
dev-gateway: ## Run gateway service locally
	cd gateway-service && mvn spring-boot:run

# ============================================
# Monitoring Commands
# ============================================

.PHONY: monitoring-up
monitoring-up: ## Start monitoring stack (Prometheus + Grafana)
	docker-compose --profile monitoring up -d
	@echo "Monitoring started. Access:"
	@echo "  - Prometheus: http://localhost:9090"
	@echo "  - Grafana: http://localhost:3000 (admin/admin)"

.PHONY: monitoring-down
monitoring-down: ## Stop monitoring stack
	docker-compose --profile monitoring down

# ============================================
# API Testing Commands
# ============================================

.PHONY: api-health
api-health: ## Check service health
	@echo "Checking service health..."
	@curl -s http://localhost:8080/actuator/health | jq '.' || echo "Gateway not available"
	@curl -s http://localhost:8081/actuator/health | jq '.' || echo "Order service not available"
	@curl -s http://localhost:8083/actuator/health | jq '.' || echo "Inventory service not available"

.PHONY: api-order
api-order: ## Create a test order
	@curl -X POST http://localhost:8080/api/orders \
		-H "Content-Type: application/json" \
		-d '{"userId":"USER-123","items":[{"sku":"ITEM-001","quantity":1,"unitPrice":99.99}]}' \
		| jq '.'

.PHONY: api-payment
api-payment: ## Simulate payment webhook
	@curl -X POST http://localhost:8084/webhooks/payment/stripe \
		-H "Content-Type: application/json" \
		-H "X-Signature: test-signature" \
		-H "X-Timestamp: $$(date +%s)" \
		-d '{"eventId":"evt_$$(date +%s)","orderId":"$(ORDER_ID)","status":"captured","amount":99.99}' \
		| jq '.'

# ============================================
# Utility Commands
# ============================================

.PHONY: format
format: ## Format code
	mvn spotless:apply

.PHONY: lint
lint: ## Lint code
	mvn spotless:check

.PHONY: deps
deps: ## Update dependencies
	mvn versions:display-dependency-updates

.PHONY: clean-logs
clean-logs: ## Clean log files
	rm -rf logs/*
	find . -name "*.log" -type f -delete

.PHONY: info
info: ## Show project information
	@echo "LMall Microservices E-commerce Platform"
	@echo "========================================="
	@echo "Java Version: 21"
	@echo "Spring Boot: 3.2.0"
	@echo "Docker Compose Version: $$(docker-compose version --short)"
	@echo ""
	@echo "Services:"
	@echo "  - Order Service: 8081"
	@echo "  - Inventory Service: 8083"
	@echo "  - Payment Service: 8084"
	@echo "  - Gateway: 8080"
	@echo ""
	@echo "Infrastructure:"
	@echo "  - PostgreSQL: 5432"
	@echo "  - Redis: 6379"
	@echo "  - RabbitMQ: 5672 (Management: 15672)"
	@echo "  - LocalStack: 4566 (AWS profile)"

# Default target
.DEFAULT_GOAL := help