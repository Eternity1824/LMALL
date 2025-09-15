#!/bin/bash
# LocalStack initialization script
# This script runs when LocalStack container starts and creates all necessary AWS resources

echo "Initializing LocalStack resources..."

# Wait for LocalStack to be ready
until awslocal sns list-topics 2>/dev/null; do
    echo "Waiting for LocalStack to be ready..."
    sleep 2
done

echo "LocalStack is ready. Creating resources..."

# Create SNS Topics
echo "Creating SNS topics..."
awslocal sns create-topic \
    --name lmall-order-events.fifo \
    --attributes FifoTopic=true,ContentBasedDeduplication=true \
    --region us-east-1

awslocal sns create-topic \
    --name lmall-payment-events.fifo \
    --attributes FifoTopic=true,ContentBasedDeduplication=true \
    --region us-east-1

awslocal sns create-topic \
    --name lmall-inventory-events \
    --region us-east-1

# Create SQS Queues and DLQs
echo "Creating SQS queues..."

# Order Service Queues
awslocal sqs create-queue \
    --queue-name order-payment-captured-dlq.fifo \
    --attributes FifoQueue=true,MessageRetentionPeriod=1209600 \
    --region us-east-1

awslocal sqs create-queue \
    --queue-name order-payment-captured.fifo \
    --attributes '{
        "FifoQueue": "true",
        "MessageRetentionPeriod": "345600",
        "VisibilityTimeout": "30",
        "RedrivePolicy": "{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:order-payment-captured-dlq.fifo\",\"maxReceiveCount\":5}"
    }' \
    --region us-east-1

awslocal sqs create-queue \
    --queue-name order-inventory-released-dlq \
    --attributes MessageRetentionPeriod=1209600 \
    --region us-east-1

awslocal sqs create-queue \
    --queue-name order-inventory-released \
    --attributes '{
        "MessageRetentionPeriod": "345600",
        "VisibilityTimeout": "30",
        "RedrivePolicy": "{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:order-inventory-released-dlq\",\"maxReceiveCount\":5}"
    }' \
    --region us-east-1

# Inventory Service Queues
awslocal sqs create-queue \
    --queue-name inventory-order-created-dlq.fifo \
    --attributes FifoQueue=true,MessageRetentionPeriod=1209600 \
    --region us-east-1

awslocal sqs create-queue \
    --queue-name inventory-order-created.fifo \
    --attributes '{
        "FifoQueue": "true",
        "MessageRetentionPeriod": "345600",
        "VisibilityTimeout": "45",
        "RedrivePolicy": "{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:inventory-order-created-dlq.fifo\",\"maxReceiveCount\":5}"
    }' \
    --region us-east-1

awslocal sqs create-queue \
    --queue-name inventory-order-cancel-requested-dlq.fifo \
    --attributes FifoQueue=true,MessageRetentionPeriod=1209600 \
    --region us-east-1

awslocal sqs create-queue \
    --queue-name inventory-order-cancel-requested.fifo \
    --attributes '{
        "FifoQueue": "true",
        "MessageRetentionPeriod": "345600",
        "VisibilityTimeout": "30",
        "RedrivePolicy": "{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:inventory-order-cancel-requested-dlq.fifo\",\"maxReceiveCount\":5}"
    }' \
    --region us-east-1

awslocal sqs create-queue \
    --queue-name inventory-order-confirmed-dlq.fifo \
    --attributes FifoQueue=true,MessageRetentionPeriod=1209600 \
    --region us-east-1

awslocal sqs create-queue \
    --queue-name inventory-order-confirmed.fifo \
    --attributes '{
        "FifoQueue": "true",
        "MessageRetentionPeriod": "345600",
        "VisibilityTimeout": "30",
        "RedrivePolicy": "{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:inventory-order-confirmed-dlq.fifo\",\"maxReceiveCount\":5}"
    }' \
    --region us-east-1

# Create SNS Subscriptions
echo "Creating SNS subscriptions..."

# Subscribe Order queues to events
awslocal sns subscribe \
    --topic-arn arn:aws:sns:us-east-1:000000000000:lmall-payment-events.fifo \
    --protocol sqs \
    --notification-endpoint arn:aws:sqs:us-east-1:000000000000:order-payment-captured.fifo \
    --attributes '{"FilterPolicy":"{\"event_type\":[\"payment.captured\"]}"}' \
    --region us-east-1

awslocal sns subscribe \
    --topic-arn arn:aws:sns:us-east-1:000000000000:lmall-inventory-events \
    --protocol sqs \
    --notification-endpoint arn:aws:sqs:us-east-1:000000000000:order-inventory-released \
    --attributes '{"FilterPolicy":"{\"event_type\":[\"inventory.released\"]}"}' \
    --region us-east-1

# Subscribe Inventory queues to events
awslocal sns subscribe \
    --topic-arn arn:aws:sns:us-east-1:000000000000:lmall-order-events.fifo \
    --protocol sqs \
    --notification-endpoint arn:aws:sqs:us-east-1:000000000000:inventory-order-created.fifo \
    --attributes '{"FilterPolicy":"{\"event_type\":[\"order.created\"]}"}' \
    --region us-east-1

awslocal sns subscribe \
    --topic-arn arn:aws:sns:us-east-1:000000000000:lmall-order-events.fifo \
    --protocol sqs \
    --notification-endpoint arn:aws:sqs:us-east-1:000000000000:inventory-order-cancel-requested.fifo \
    --attributes '{"FilterPolicy":"{\"event_type\":[\"order.cancel.requested\"]}"}' \
    --region us-east-1

awslocal sns subscribe \
    --topic-arn arn:aws:sns:us-east-1:000000000000:lmall-order-events.fifo \
    --protocol sqs \
    --notification-endpoint arn:aws:sqs:us-east-1:000000000000:inventory-order-confirmed.fifo \
    --attributes '{"FilterPolicy":"{\"event_type\":[\"order.confirmed\"]}"}' \
    --region us-east-1

# Create DynamoDB Tables
echo "Creating DynamoDB tables..."

awslocal dynamodb create-table \
    --table-name order-timeouts-local \
    --attribute-definitions \
        AttributeName=order_id,AttributeType=S \
        AttributeName=timeout_at,AttributeType=N \
        AttributeName=status,AttributeType=S \
    --key-schema \
        AttributeName=order_id,KeyType=HASH \
    --global-secondary-indexes \
        IndexName=timeout-index,Keys=["{AttributeName=status,KeyType=HASH}","{AttributeName=timeout_at,KeyType=RANGE}"],Projection="{ProjectionType=ALL}" \
    --billing-mode PAY_PER_REQUEST \
    --region us-east-1

awslocal dynamodb create-table \
    --table-name hot-products-local \
    --attribute-definitions \
        AttributeName=item_id,AttributeType=S \
    --key-schema \
        AttributeName=item_id,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region us-east-1

# Create S3 Buckets
echo "Creating S3 buckets..."

awslocal s3 mb s3://lmall-dlq-archive-local --region us-east-1
awslocal s3 mb s3://lmall-lambda-artifacts-local --region us-east-1

# Create Secrets Manager Secrets
echo "Creating secrets..."

awslocal secretsmanager create-secret \
    --name lmall/db/password \
    --secret-string '{"username":"postgres","password":"postgres"}' \
    --region us-east-1

# Create EventBridge Rules (for Lambda scheduling)
echo "Creating EventBridge rules..."

awslocal events put-rule \
    --name order-timeout-scanner \
    --schedule-expression "rate(1 minute)" \
    --state ENABLED \
    --description "Scan for expired order payment timeouts" \
    --region us-east-1

awslocal events put-rule \
    --name dlq-replay-schedule \
    --schedule-expression "rate(15 minutes)" \
    --state DISABLED \
    --description "Periodic DLQ replay for failed messages" \
    --region us-east-1

echo "LocalStack initialization complete!"

# List created resources for verification
echo ""
echo "Created resources:"
echo "=================="
echo "SNS Topics:"
awslocal sns list-topics --region us-east-1 | jq -r '.Topics[].TopicArn'
echo ""
echo "SQS Queues:"
awslocal sqs list-queues --region us-east-1 | jq -r '.QueueUrls[]'
echo ""
echo "DynamoDB Tables:"
awslocal dynamodb list-tables --region us-east-1 | jq -r '.TableNames[]'
echo ""
echo "S3 Buckets:"
awslocal s3 ls
echo ""
echo "EventBridge Rules:"
awslocal events list-rules --region us-east-1 | jq -r '.Rules[].Name'