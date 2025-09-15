-- LMall Database Initialization Script
-- This script creates the necessary schemas and tables for all services

-- Create database if not exists (run as superuser)
-- CREATE DATABASE lmall;

-- Use the lmall database
\c lmall;

-- Create schemas for each service
CREATE SCHEMA IF NOT EXISTS order_service;
CREATE SCHEMA IF NOT EXISTS inventory_service;
CREATE SCHEMA IF NOT EXISTS payment_service;
CREATE SCHEMA IF NOT EXISTS user_service;
CREATE SCHEMA IF NOT EXISTS product_service;

-- Set search path
SET search_path TO order_service, inventory_service, payment_service, public;

-- ============================================
-- Order Service Tables
-- ============================================

-- Orders table
CREATE TABLE IF NOT EXISTS order_service.orders (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'USD',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    paid_at TIMESTAMP,
    confirmed_at TIMESTAMP,
    canceled_at TIMESTAMP,
    cancel_reason VARCHAR(255),
    version INT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_orders_user_id ON order_service.orders(user_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON order_service.orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_created_at ON order_service.orders(created_at);

-- Order items table
CREATE TABLE IF NOT EXISTS order_service.order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    product_name VARCHAR(255),
    quantity INT NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    subtotal DECIMAL(10,2) NOT NULL,
    FOREIGN KEY (order_id) REFERENCES order_service.orders(id)
);

CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_service.order_items(order_id);

-- Order timeouts table
CREATE TABLE IF NOT EXISTS order_service.order_timeouts (
    order_id VARCHAR(64) PRIMARY KEY,
    timeout_at TIMESTAMP NOT NULL,
    processed BOOLEAN DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_timeouts_timeout_at ON order_service.order_timeouts(timeout_at, processed);

-- Outbox events table (Order Service)
CREATE TABLE IF NOT EXISTS order_service.outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP,
    retry_count INT DEFAULT 0,
    error_message TEXT,
    message_id VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_order_outbox_status ON order_service.outbox_events(status);
CREATE INDEX IF NOT EXISTS idx_order_outbox_created ON order_service.outbox_events(created_at);

-- Consumer dedupe table (Order Service)
CREATE TABLE IF NOT EXISTS order_service.consumer_dedupe (
    consumer_name VARCHAR(100) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_name, message_id)
);

CREATE INDEX IF NOT EXISTS idx_order_dedupe_processed ON order_service.consumer_dedupe(processed_at);

-- ============================================
-- Inventory Service Tables
-- ============================================

-- Inventory table
CREATE TABLE IF NOT EXISTS inventory_service.inventory (
    item_id VARCHAR(64) PRIMARY KEY,
    product_name VARCHAR(255),
    available INT NOT NULL DEFAULT 0,
    reserved INT NOT NULL DEFAULT 0,
    sold INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_inventory_available ON inventory_service.inventory(available);

-- Redis outbox table (for Redis operation recovery)
CREATE TABLE IF NOT EXISTS inventory_service.redis_outbox (
    id BIGSERIAL PRIMARY KEY,
    operation_type VARCHAR(50) NOT NULL,
    item_id VARCHAR(64) NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    retry_count INT DEFAULT 0,
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_redis_outbox_status ON inventory_service.redis_outbox(status);
CREATE INDEX IF NOT EXISTS idx_redis_outbox_created ON inventory_service.redis_outbox(created_at);

-- Outbox events table (Inventory Service)
CREATE TABLE IF NOT EXISTS inventory_service.outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP,
    retry_count INT DEFAULT 0,
    error_message TEXT,
    message_id VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_inv_outbox_status ON inventory_service.outbox_events(status);
CREATE INDEX IF NOT EXISTS idx_inv_outbox_created ON inventory_service.outbox_events(created_at);

-- Consumer dedupe table (Inventory Service)
CREATE TABLE IF NOT EXISTS inventory_service.consumer_dedupe (
    consumer_name VARCHAR(100) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_name, message_id)
);

CREATE INDEX IF NOT EXISTS idx_inv_dedupe_processed ON inventory_service.consumer_dedupe(processed_at);

-- ============================================
-- Payment Service Tables
-- ============================================

-- Payments table
CREATE TABLE IF NOT EXISTS payment_service.payments (
    id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'USD',
    status VARCHAR(20) NOT NULL,
    payment_method VARCHAR(50),
    gateway VARCHAR(50),
    gateway_transaction_id VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payments_order_id ON payment_service.payments(order_id);
CREATE INDEX IF NOT EXISTS idx_payments_user_id ON payment_service.payments(user_id);
CREATE INDEX IF NOT EXISTS idx_payments_status ON payment_service.payments(status);

-- Payment webhook events table (for idempotency)
CREATE TABLE IF NOT EXISTS payment_service.payment_events (
    event_id VARCHAR(255) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payment_events_order_id ON payment_service.payment_events(order_id);

-- Outbox events table (Payment Service)
CREATE TABLE IF NOT EXISTS payment_service.outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP,
    retry_count INT DEFAULT 0,
    error_message TEXT,
    message_id VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_pay_outbox_status ON payment_service.outbox_events(status);
CREATE INDEX IF NOT EXISTS idx_pay_outbox_created ON payment_service.outbox_events(created_at);

-- Consumer dedupe table (Payment Service)
CREATE TABLE IF NOT EXISTS payment_service.consumer_dedupe (
    consumer_name VARCHAR(100) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_name, message_id)
);

CREATE INDEX IF NOT EXISTS idx_pay_dedupe_processed ON payment_service.consumer_dedupe(processed_at);

-- ============================================
-- Product Service Tables (Basic)
-- ============================================

CREATE TABLE IF NOT EXISTS product_service.products (
    id VARCHAR(64) PRIMARY KEY,
    sku VARCHAR(64) UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price DECIMAL(10,2) NOT NULL,
    category VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_products_sku ON product_service.products(sku);
CREATE INDEX IF NOT EXISTS idx_products_category ON product_service.products(category);

-- ============================================
-- User Service Tables (Basic)
-- ============================================

CREATE TABLE IF NOT EXISTS user_service.users (
    id VARCHAR(64) PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    username VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_users_email ON user_service.users(email);
CREATE INDEX IF NOT EXISTS idx_users_username ON user_service.users(username);

-- ============================================
-- Sample Data
-- ============================================

-- Insert sample products
INSERT INTO product_service.products (id, sku, name, price, category) VALUES
    ('PROD-001', 'ITEM-001', 'Laptop Pro 15"', 1299.99, 'Electronics'),
    ('PROD-002', 'ITEM-002', 'Wireless Mouse', 29.99, 'Electronics'),
    ('PROD-003', 'ITEM-003', 'USB-C Hub', 49.99, 'Electronics'),
    ('PROD-004', 'ITEM-004', 'Mechanical Keyboard', 149.99, 'Electronics'),
    ('PROD-005', 'ITEM-005', 'Monitor 27"', 399.99, 'Electronics')
ON CONFLICT (id) DO NOTHING;

-- Insert sample inventory
INSERT INTO inventory_service.inventory (item_id, product_name, available, reserved, sold) VALUES
    ('ITEM-001', 'Laptop Pro 15"', 100, 0, 0),
    ('ITEM-002', 'Wireless Mouse', 500, 0, 0),
    ('ITEM-003', 'USB-C Hub', 200, 0, 0),
    ('ITEM-004', 'Mechanical Keyboard', 150, 0, 0),
    ('ITEM-005', 'Monitor 27"', 75, 0, 0)
ON CONFLICT (item_id) DO NOTHING;

-- Insert sample users
INSERT INTO user_service.users (id, email, username, password_hash, first_name, last_name) VALUES
    ('USER-001', 'john.doe@example.com', 'johndoe', '$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx', 'John', 'Doe'),
    ('USER-002', 'jane.smith@example.com', 'janesmith', '$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx', 'Jane', 'Smith'),
    ('USER-003', 'test.user@example.com', 'testuser', '$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx', 'Test', 'User')
ON CONFLICT (id) DO NOTHING;

-- Grant permissions
GRANT ALL PRIVILEGES ON SCHEMA order_service TO postgres;
GRANT ALL PRIVILEGES ON SCHEMA inventory_service TO postgres;
GRANT ALL PRIVILEGES ON SCHEMA payment_service TO postgres;
GRANT ALL PRIVILEGES ON SCHEMA product_service TO postgres;
GRANT ALL PRIVILEGES ON SCHEMA user_service TO postgres;

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA order_service TO postgres;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA inventory_service TO postgres;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA payment_service TO postgres;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA product_service TO postgres;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA user_service TO postgres;

GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA order_service TO postgres;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA inventory_service TO postgres;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA payment_service TO postgres;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA product_service TO postgres;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA user_service TO postgres;

-- Success message
DO $$
BEGIN
    RAISE NOTICE 'Database initialization completed successfully!';
END
$$;