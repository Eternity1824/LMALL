# Lambda Functions Implementation

## Overview

Lambda functions handle non-real-time tasks in the LMall architecture:
- **Order cancellation scheduling**
- **DLQ message replay**
- **Cache prewarming**
- **Cleanup and maintenance tasks**

## Function Implementations

### 1. Order Cancel Scheduler

**Purpose**: Scan for expired payment timeouts and publish cancellation events

**Trigger**: EventBridge rule (every 60 seconds)

**Runtime**: Java 17 with GraalVM native image

```java
package com.lmall.lambda.cancel;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

public class CancelSchedulerHandler implements RequestHandler<ScheduledEvent, CancelSchedulerResponse> {

    private final DynamoDbClient dynamoClient;
    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final String timeoutTableName;
    private final String orderEventsTopicArn;

    public CancelSchedulerHandler() {
        this.dynamoClient = DynamoDbClient.builder()
            .region(Region.of(System.getenv("AWS_REGION")))
            .build();
        this.snsClient = SnsClient.builder()
            .region(Region.of(System.getenv("AWS_REGION")))
            .build();
        this.objectMapper = new ObjectMapper();
        this.timeoutTableName = System.getenv("TIMEOUT_TABLE_NAME");
        this.orderEventsTopicArn = System.getenv("ORDER_EVENTS_TOPIC_ARN");
    }

    @Override
    public CancelSchedulerResponse handleRequest(ScheduledEvent event, Context context) {
        context.getLogger().log("Starting order timeout scan at: " + event.getTime());

        CancelSchedulerResponse response = new CancelSchedulerResponse();
        List<String> processedOrders = new ArrayList<>();
        List<String> failedOrders = new ArrayList<>();

        try {
            // Query expired timeouts
            long currentTime = Instant.now().toEpochMilli();

            QueryRequest queryRequest = QueryRequest.builder()
                .tableName(timeoutTableName)
                .indexName("timeout-index")
                .keyConditionExpression("status = :pending AND timeout_at < :now")
                .expressionAttributeValues(Map.of(
                    ":pending", AttributeValue.builder().s("PENDING").build(),
                    ":now", AttributeValue.builder().n(String.valueOf(currentTime)).build()
                ))
                .limit(100)  // Process max 100 timeouts per invocation
                .build();

            QueryResponse queryResponse = dynamoClient.query(queryRequest);

            for (Map<String, AttributeValue> item : queryResponse.items()) {
                String orderId = item.get("order_id").s();

                try {
                    // Check if order is still in PENDING state
                    if (!isOrderStillPending(orderId)) {
                        // Order already processed, mark timeout as obsolete
                        markTimeoutAsObsolete(orderId);
                        continue;
                    }

                    // Create cancellation event
                    OrderCancelRequestedEvent cancelEvent = OrderCancelRequestedEvent.builder()
                        .orderId(orderId)
                        .reason("PAYMENT_TIMEOUT")
                        .timestamp(Instant.now().toString())
                        .source("lambda:cancel-scheduler")
                        .build();

                    // Publish to SNS
                    PublishRequest publishRequest = PublishRequest.builder()
                        .topicArn(orderEventsTopicArn)
                        .message(objectMapper.writeValueAsString(cancelEvent))
                        .messageGroupId(orderId)  // FIFO ordering by order
                        .messageDeduplicationId(UUID.randomUUID().toString())
                        .messageAttributes(Map.of(
                            "event_type", MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue("order.cancel.requested")
                                .build(),
                            "aggregate_id", MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(orderId)
                                .build(),
                            "source", MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue("timeout-scheduler")
                                .build()
                        ))
                        .build();

                    PublishResponse publishResponse = snsClient.publish(publishRequest);

                    // Mark timeout as processed
                    markTimeoutAsProcessed(orderId, publishResponse.messageId());

                    processedOrders.add(orderId);
                    context.getLogger().log("Published cancel request for order: " + orderId);

                } catch (Exception e) {
                    context.getLogger().log("Failed to process timeout for order " + orderId + ": " + e.getMessage());
                    failedOrders.add(orderId);

                    // Update retry count
                    incrementRetryCount(orderId);
                }
            }

            // Check for stuck orders (timeout > 1 hour ago but not processed)
            checkAndAlertStuckOrders(context);

        } catch (Exception e) {
            context.getLogger().log("Error in timeout scanner: " + e.getMessage());
            throw new RuntimeException("Timeout scanner failed", e);
        }

        response.setProcessedCount(processedOrders.size());
        response.setFailedCount(failedOrders.size());
        response.setProcessedOrderIds(processedOrders);
        response.setFailedOrderIds(failedOrders);

        context.getLogger().log(String.format("Completed: processed=%d, failed=%d",
            processedOrders.size(), failedOrders.size()));

        return response;
    }

    private boolean isOrderStillPending(String orderId) {
        // Query order status from DynamoDB or via internal API
        GetItemRequest request = GetItemRequest.builder()
            .tableName("orders")
            .key(Map.of("order_id", AttributeValue.builder().s(orderId).build()))
            .projectionExpression("order_status")
            .build();

        GetItemResponse response = dynamoClient.getItem(request);

        if (response.item() == null || response.item().isEmpty()) {
            return false;
        }

        String status = response.item().get("order_status").s();
        return "PENDING".equals(status);
    }

    private void markTimeoutAsProcessed(String orderId, String messageId) {
        UpdateItemRequest request = UpdateItemRequest.builder()
            .tableName(timeoutTableName)
            .key(Map.of("order_id", AttributeValue.builder().s(orderId).build()))
            .updateExpression("SET #status = :processed, processed_at = :now, message_id = :msgId")
            .expressionAttributeNames(Map.of("#status", "status"))
            .expressionAttributeValues(Map.of(
                ":processed", AttributeValue.builder().s("PROCESSED").build(),
                ":now", AttributeValue.builder().n(String.valueOf(Instant.now().toEpochMilli())).build(),
                ":msgId", AttributeValue.builder().s(messageId).build()
            ))
            .build();

        dynamoClient.updateItem(request);
    }

    // Data classes
    @Data
    @Builder
    public static class OrderCancelRequestedEvent {
        private String orderId;
        private String reason;
        private String timestamp;
        private String source;
    }

    @Data
    public static class CancelSchedulerResponse {
        private int processedCount;
        private int failedCount;
        private List<String> processedOrderIds;
        private List<String> failedOrderIds;
    }
}
```

### 2. DLQ Replay Handler

**Purpose**: Replay failed messages from DLQ with idempotency

**Trigger**: Manual invocation or scheduled EventBridge rule

```java
package com.lmall.lambda.dlq;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.Instant;

public class DLQReplayHandler implements RequestHandler<DLQReplayRequest, DLQReplayResponse> {

    private final SqsClient sqsClient;
    private final SnsClient snsClient;
    private final CloudWatchClient cloudWatchClient;
    private final ObjectMapper objectMapper;
    private final int maxReplayAttempts = 3;

    public DLQReplayHandler() {
        this.sqsClient = SqsClient.builder().build();
        this.snsClient = SnsClient.builder().build();
        this.cloudWatchClient = CloudWatchClient.builder().build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public DLQReplayResponse handleRequest(DLQReplayRequest request, Context context) {
        context.getLogger().log("Starting DLQ replay for queues: " + request.getQueueNames());

        DLQReplayResponse response = new DLQReplayResponse();
        Map<String, ReplayResult> results = new HashMap<>();

        for (String queueName : request.getQueueNames()) {
            ReplayResult result = replayQueue(queueName, request.getMaxMessages(), context);
            results.put(queueName, result);

            // Emit CloudWatch metrics
            emitMetrics(queueName, result);
        }

        response.setResults(results);
        response.setTotalReplayed(
            results.values().stream()
                .mapToInt(ReplayResult::getSuccessCount)
                .sum()
        );

        context.getLogger().log("DLQ replay completed: " + response);
        return response;
    }

    private ReplayResult replayQueue(String queueName, int maxMessages, Context context) {
        ReplayResult result = new ReplayResult();
        String queueUrl = getQueueUrl(queueName);

        try {
            // Receive messages from DLQ
            ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(Math.min(maxMessages, 10))  // SQS max is 10
                .waitTimeSeconds(2)
                .messageAttributeNames("All")
                .attributeNames(QueueAttributeName.ALL)
                .build();

            ReceiveMessageResponse receiveResponse = sqsClient.receiveMessage(receiveRequest);
            List<Message> messages = receiveResponse.messages();

            for (Message message : messages) {
                try {
                    // Parse original message
                    Map<String, Object> originalMessage = objectMapper.readValue(
                        message.body(),
                        Map.class
                    );

                    // Check replay attempts
                    int replayAttempts = getReplayAttempts(message);
                    if (replayAttempts >= maxReplayAttempts) {
                        context.getLogger().log("Message exceeded max replay attempts: " + message.messageId());
                        moveToDeadDeadLetterQueue(message, queueUrl);
                        result.incrementSkipped();
                        continue;
                    }

                    // Determine target topic based on event type
                    String eventType = (String) originalMessage.get("event_type");
                    String topicArn = getTopicArnForEventType(eventType);

                    // Add replay metadata
                    Map<String, MessageAttributeValue> attributes = new HashMap<>();
                    attributes.put("event_type", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(eventType)
                        .build());
                    attributes.put("replay", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue("true")
                        .build());
                    attributes.put("replay_attempt", MessageAttributeValue.builder()
                        .dataType("Number")
                        .stringValue(String.valueOf(replayAttempts + 1))
                        .build());
                    attributes.put("replay_time", MessageAttributeValue.builder()
                        .dataType("Number")
                        .stringValue(String.valueOf(System.currentTimeMillis()))
                        .build());
                    attributes.put("original_message_id", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(message.messageId())
                        .build());
                    attributes.put("dlq_source", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(queueName)
                        .build());

                    // Republish to SNS
                    PublishRequest publishRequest = PublishRequest.builder()
                        .topicArn(topicArn)
                        .message(objectMapper.writeValueAsString(originalMessage.get("payload")))
                        .messageGroupId((String) originalMessage.get("aggregate_id"))
                        .messageDeduplicationId(UUID.randomUUID().toString())
                        .messageAttributes(attributes)
                        .build();

                    snsClient.publish(publishRequest);

                    // Delete from DLQ after successful replay
                    DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build();

                    sqsClient.deleteMessage(deleteRequest);

                    result.incrementSuccess();
                    context.getLogger().log("Successfully replayed message: " + message.messageId());

                } catch (Exception e) {
                    context.getLogger().log("Failed to replay message " + message.messageId() + ": " + e.getMessage());
                    result.incrementFailed();
                    result.addError(message.messageId(), e.getMessage());

                    // Change visibility timeout to retry later
                    changeMessageVisibility(message, queueUrl, 300);  // 5 minutes
                }
            }

        } catch (Exception e) {
            context.getLogger().log("Error processing queue " + queueName + ": " + e.getMessage());
            result.setError(e.getMessage());
        }

        return result;
    }

    private void emitMetrics(String queueName, ReplayResult result) {
        List<MetricDatum> metrics = new ArrayList<>();

        metrics.add(MetricDatum.builder()
            .metricName("DLQReplaySuccess")
            .value((double) result.getSuccessCount())
            .timestamp(Instant.now())
            .dimensions(Dimension.builder()
                .name("QueueName")
                .value(queueName)
                .build())
            .build());

        metrics.add(MetricDatum.builder()
            .metricName("DLQReplayFailed")
            .value((double) result.getFailedCount())
            .timestamp(Instant.now())
            .dimensions(Dimension.builder()
                .name("QueueName")
                .value(queueName)
                .build())
            .build());

        PutMetricDataRequest request = PutMetricDataRequest.builder()
            .namespace("LMall/Lambda")
            .metricData(metrics)
            .build();

        cloudWatchClient.putMetricData(request);
    }

    // Request/Response classes
    @Data
    public static class DLQReplayRequest {
        private List<String> queueNames;
        private int maxMessages = 100;
        private boolean dryRun = false;
    }

    @Data
    public static class DLQReplayResponse {
        private Map<String, ReplayResult> results;
        private int totalReplayed;
    }

    @Data
    public static class ReplayResult {
        private int successCount = 0;
        private int failedCount = 0;
        private int skippedCount = 0;
        private String error;
        private Map<String, String> errors = new HashMap<>();

        public void incrementSuccess() { successCount++; }
        public void incrementFailed() { failedCount++; }
        public void incrementSkipped() { skippedCount++; }
        public void addError(String messageId, String error) {
            errors.put(messageId, error);
        }
    }
}
```

### 3. Cache Prewarmer

**Purpose**: Prewarm Redis cache with hot products after deployment

**Trigger**: Post-deployment hook or manual invocation

```java
package com.lmall.lambda.cache;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;

public class CachePrewarmerHandler implements RequestHandler<PrewarmRequest, PrewarmResponse> {

    private final DynamoDbClient dynamoClient;
    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;

    public CachePrewarmerHandler() {
        this.dynamoClient = DynamoDbClient.builder().build();
        this.jedisPool = new JedisPool(
            System.getenv("REDIS_HOST"),
            Integer.parseInt(System.getenv("REDIS_PORT", "6379"))
        );
        this.objectMapper = new ObjectMapper();
        this.executorService = Executors.newFixedThreadPool(10);
    }

    @Override
    public PrewarmResponse handleRequest(PrewarmRequest request, Context context) {
        context.getLogger().log("Starting cache prewarm for " + request.getItemCount() + " items");

        PrewarmResponse response = new PrewarmResponse();
        long startTime = System.currentTimeMillis();

        try {
            // Get hot items from analytics or configuration
            List<String> hotItemIds = getHotItems(request.getItemCount());
            context.getLogger().log("Found " + hotItemIds.size() + " hot items to prewarm");

            // Batch fetch from DynamoDB
            List<Map<String, AttributeValue>> items = batchGetItems(hotItemIds);

            // Parallel cache warming
            List<CompletableFuture<CacheResult>> futures = new ArrayList<>();

            for (Map<String, AttributeValue> item : items) {
                CompletableFuture<CacheResult> future = CompletableFuture.supplyAsync(() ->
                    warmCacheForItem(item, context),
                    executorService
                );
                futures.add(future);
            }

            // Wait for all cache operations
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            );

            allFutures.get(30, TimeUnit.SECONDS);  // 30 second timeout

            // Collect results
            int successCount = 0;
            int failureCount = 0;
            List<String> errors = new ArrayList<>();

            for (CompletableFuture<CacheResult> future : futures) {
                try {
                    CacheResult result = future.get();
                    if (result.isSuccess()) {
                        successCount++;
                    } else {
                        failureCount++;
                        errors.add(result.getError());
                    }
                } catch (Exception e) {
                    failureCount++;
                    errors.add(e.getMessage());
                }
            }

            // Prewarm inventory data
            prewarmInventoryData(hotItemIds, context);

            // Set response
            response.setSuccessCount(successCount);
            response.setFailureCount(failureCount);
            response.setDurationMs(System.currentTimeMillis() - startTime);
            response.setErrors(errors);

            context.getLogger().log(String.format("Cache prewarm completed: success=%d, failure=%d, duration=%dms",
                successCount, failureCount, response.getDurationMs()));

        } catch (Exception e) {
            context.getLogger().log("Cache prewarm failed: " + e.getMessage());
            response.setError(e.getMessage());
        } finally {
            executorService.shutdown();
        }

        return response;
    }

    private CacheResult warmCacheForItem(Map<String, AttributeValue> item, Context context) {
        CacheResult result = new CacheResult();
        String itemId = item.get("item_id").s();

        try (Jedis jedis = jedisPool.getResource()) {
            // Cache product details
            String productKey = "product:" + itemId;
            Map<String, String> productData = new HashMap<>();
            productData.put("id", itemId);
            productData.put("name", item.get("name").s());
            productData.put("price", item.get("price").n());
            productData.put("category", item.get("category").s());

            jedis.hset(productKey, productData);
            jedis.expire(productKey, 3600);  // 1 hour TTL

            // Cache inventory if available
            if (item.containsKey("inventory")) {
                String inventoryKey = "inventory:" + itemId;
                Map<String, String> inventoryData = new HashMap<>();
                inventoryData.put("available", item.get("available").n());
                inventoryData.put("reserved", "0");
                inventoryData.put("sold", item.get("sold").n());

                jedis.hset(inventoryKey, inventoryData);
                jedis.expire(inventoryKey, 3600);
            }

            result.setSuccess(true);
            result.setItemId(itemId);

        } catch (Exception e) {
            context.getLogger().log("Failed to cache item " + itemId + ": " + e.getMessage());
            result.setSuccess(false);
            result.setError("Item " + itemId + ": " + e.getMessage());
        }

        return result;
    }

    private void prewarmInventoryData(List<String> itemIds, Context context) {
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();

            for (String itemId : itemIds) {
                // Set initial inventory reservation keys
                String holdKey = "hold:*:" + itemId;
                pipeline.del(holdKey);  // Clear any stale holds

                // Initialize inventory counters
                String counterKey = "inventory:counter:" + itemId;
                pipeline.set(counterKey, "0");
            }

            pipeline.sync();
            context.getLogger().log("Prewarmed inventory data for " + itemIds.size() + " items");

        } catch (Exception e) {
            context.getLogger().log("Failed to prewarm inventory data: " + e.getMessage());
        }
    }

    private List<String> getHotItems(int count) {
        // Query hot items from analytics table or use predefined list
        ScanRequest scanRequest = ScanRequest.builder()
            .tableName("hot_products")
            .limit(count)
            .projectionExpression("item_id")
            .build();

        ScanResponse response = dynamoClient.scan(scanRequest);
        List<String> itemIds = new ArrayList<>();

        for (Map<String, AttributeValue> item : response.items()) {
            itemIds.add(item.get("item_id").s());
        }

        return itemIds;
    }

    // Data classes
    @Data
    public static class PrewarmRequest {
        private int itemCount = 100;
        private List<String> specificItemIds;
        private boolean includeInventory = true;
    }

    @Data
    public static class PrewarmResponse {
        private int successCount;
        private int failureCount;
        private long durationMs;
        private String error;
        private List<String> errors;
    }

    @Data
    private static class CacheResult {
        private boolean success;
        private String itemId;
        private String error;
    }
}
```

### 4. Data Cleanup Handler

**Purpose**: Clean up old data from outbox, dedupe tables, and expired cache

**Trigger**: Daily EventBridge schedule

```java
package com.lmall.lambda.cleanup;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.rds.RdsDataClient;
import software.amazon.awssdk.services.rds.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public class DataCleanupHandler implements RequestHandler<CleanupRequest, CleanupResponse> {

    private final RdsDataClient rdsClient;
    private final S3Client s3Client;
    private final String clusterArn;
    private final String secretArn;
    private final String databaseName;

    public DataCleanupHandler() {
        this.rdsClient = RdsDataClient.builder().build();
        this.s3Client = S3Client.builder().build();
        this.clusterArn = System.getenv("RDS_CLUSTER_ARN");
        this.secretArn = System.getenv("RDS_SECRET_ARN");
        this.databaseName = System.getenv("DATABASE_NAME");
    }

    @Override
    public CleanupResponse handleRequest(CleanupRequest request, Context context) {
        context.getLogger().log("Starting data cleanup with retention days: " + request.getRetentionDays());

        CleanupResponse response = new CleanupResponse();
        long cutoffTime = LocalDateTime.now()
            .minusDays(request.getRetentionDays())
            .toEpochSecond(ZoneOffset.UTC);

        try {
            // Clean outbox events
            int outboxDeleted = cleanOutboxEvents(cutoffTime, context);
            response.setOutboxEventsDeleted(outboxDeleted);

            // Clean consumer dedupe
            int dedupeDeleted = cleanConsumerDedupe(cutoffTime, context);
            response.setDedupeRecordsDeleted(dedupeDeleted);

            // Clean processed timeouts
            int timeoutsDeleted = cleanProcessedTimeouts(cutoffTime, context);
            response.setTimeoutsDeleted(timeoutsDeleted);

            // Archive old DLQ messages to S3
            if (request.isArchiveDlq()) {
                int archived = archiveDlqMessages(cutoffTime, context);
                response.setDlqMessagesArchived(archived);
            }

            context.getLogger().log(String.format("Cleanup completed: outbox=%d, dedupe=%d, timeouts=%d",
                outboxDeleted, dedupeDeleted, timeoutsDeleted));

        } catch (Exception e) {
            context.getLogger().log("Cleanup failed: " + e.getMessage());
            response.setError(e.getMessage());
        }

        return response;
    }

    private int cleanOutboxEvents(long cutoffTime, Context context) {
        String sql = "DELETE FROM outbox_events WHERE status = 'SENT' AND sent_at < :cutoff";

        ExecuteStatementRequest request = ExecuteStatementRequest.builder()
            .resourceArn(clusterArn)
            .secretArn(secretArn)
            .database(databaseName)
            .sql(sql)
            .parameters(
                SqlParameter.builder()
                    .name("cutoff")
                    .value(Field.builder()
                        .longValue(cutoffTime)
                        .build())
                    .build()
            )
            .build();

        ExecuteStatementResponse response = rdsClient.executeStatement(request);
        int deleted = response.numberOfRecordsUpdated().intValue();

        context.getLogger().log("Deleted " + deleted + " outbox events");
        return deleted;
    }

    private int cleanConsumerDedupe(long cutoffTime, Context context) {
        String sql = "DELETE FROM consumer_dedupe WHERE processed_at < :cutoff";

        ExecuteStatementRequest request = ExecuteStatementRequest.builder()
            .resourceArn(clusterArn)
            .secretArn(secretArn)
            .database(databaseName)
            .sql(sql)
            .parameters(
                SqlParameter.builder()
                    .name("cutoff")
                    .value(Field.builder()
                        .longValue(cutoffTime)
                        .build())
                    .build()
            )
            .build();

        ExecuteStatementResponse response = rdsClient.executeStatement(request);
        int deleted = response.numberOfRecordsUpdated().intValue();

        context.getLogger().log("Deleted " + deleted + " dedupe records");
        return deleted;
    }

    // Data classes
    @Data
    public static class CleanupRequest {
        private int retentionDays = 30;
        private boolean archiveDlq = true;
        private boolean dryRun = false;
    }

    @Data
    public static class CleanupResponse {
        private int outboxEventsDeleted;
        private int dedupeRecordsDeleted;
        private int timeoutsDeleted;
        private int dlqMessagesArchived;
        private String error;
    }
}
```

## Lambda Configuration

### Serverless Framework Configuration

```yaml
service: lmall-lambda-functions

provider:
  name: aws
  runtime: java17
  region: ${opt:region, 'us-east-1'}
  stage: ${opt:stage, 'dev'}
  environment:
    STAGE: ${self:provider.stage}

functions:
  cancelScheduler:
    handler: com.lmall.lambda.cancel.CancelSchedulerHandler
    memorySize: 512
    timeout: 60
    reservedConcurrency: 2
    events:
      - schedule:
          rate: rate(1 minute)
          enabled: true
    environment:
      TIMEOUT_TABLE_NAME: ${self:custom.tables.timeouts}
      ORDER_EVENTS_TOPIC_ARN: !Ref OrderEventsTopic
    iamRoleStatements:
      - Effect: Allow
        Action:
          - dynamodb:Query
          - dynamodb:UpdateItem
        Resource: !GetAtt TimeoutsTable.Arn
      - Effect: Allow
        Action:
          - sns:Publish
        Resource: !Ref OrderEventsTopic

  dlqReplay:
    handler: com.lmall.lambda.dlq.DLQReplayHandler
    memorySize: 1024
    timeout: 300
    environment:
      MAX_REPLAY_ATTEMPTS: 3
    events:
      - schedule:
          rate: rate(15 minutes)
          enabled: false  # Enable only when needed
          input:
            queueNames:
              - order-payment-captured-dlq.fifo
              - inventory-order-created-dlq.fifo
            maxMessages: 50
    iamRoleStatements:
      - Effect: Allow
        Action:
          - sqs:ReceiveMessage
          - sqs:DeleteMessage
          - sqs:ChangeMessageVisibility
        Resource: arn:aws:sqs:*:*:*-dlq*
      - Effect: Allow
        Action:
          - sns:Publish
        Resource: arn:aws:sns:*:*:lmall-*

  cachePrewarmer:
    handler: com.lmall.lambda.cache.CachePrewarmerHandler
    memorySize: 2048
    timeout: 120
    vpc:
      securityGroupIds:
        - ${self:custom.securityGroups.lambda}
      subnetIds:
        - ${self:custom.subnets.private1}
        - ${self:custom.subnets.private2}
    environment:
      REDIS_HOST: ${self:custom.redis.host}
      REDIS_PORT: ${self:custom.redis.port}
    events:
      - eventBridge:
          eventBus: default
          pattern:
            source:
              - aws.codedeploy
            detail-type:
              - CodeDeploy Deployment State-change Notification
            detail:
              state:
                - SUCCESS

  dataCleanup:
    handler: com.lmall.lambda.cleanup.DataCleanupHandler
    memorySize: 512
    timeout: 300
    events:
      - schedule:
          rate: cron(0 3 * * ? *)  # Daily at 3 AM UTC
          input:
            retentionDays: 30
            archiveDlq: true
    environment:
      RDS_CLUSTER_ARN: ${self:custom.rds.clusterArn}
      RDS_SECRET_ARN: ${self:custom.rds.secretArn}
      DATABASE_NAME: lmall

plugins:
  - serverless-plugin-lambda-insights
  - serverless-plugin-tracing

custom:
  tables:
    timeouts: order-timeouts-${self:provider.stage}
  redis:
    host: !GetAtt ElastiCacheCluster.RedisEndpoint.Address
    port: 6379
```

### SAM Template

```yaml
AWSTemplateFormatVersion: '2010-09-09'
Transform: AWS::Serverless-2016-10-31

Globals:
  Function:
    Runtime: java17
    Timeout: 60
    MemorySize: 512
    Tracing: Active
    Environment:
      Variables:
        STAGE: !Ref Stage

Parameters:
  Stage:
    Type: String
    Default: dev

Resources:
  CancelSchedulerFunction:
    Type: AWS::Serverless::Function
    Properties:
      FunctionName: !Sub lmall-cancel-scheduler-${Stage}
      Handler: com.lmall.lambda.cancel.CancelSchedulerHandler
      CodeUri: target/lambda.jar
      Events:
        ScheduleEvent:
          Type: Schedule
          Properties:
            Schedule: rate(1 minute)
            Enabled: true
      Environment:
        Variables:
          TIMEOUT_TABLE_NAME: !Ref TimeoutsTable
          ORDER_EVENTS_TOPIC_ARN: !Ref OrderEventsTopic
      Policies:
        - DynamoDBCrudPolicy:
            TableName: !Ref TimeoutsTable
        - SNSPublishMessagePolicy:
            TopicName: !GetAtt OrderEventsTopic.TopicName

  DLQReplayFunction:
    Type: AWS::Serverless::Function
    Properties:
      FunctionName: !Sub lmall-dlq-replay-${Stage}
      Handler: com.lmall.lambda.dlq.DLQReplayHandler
      CodeUri: target/lambda.jar
      MemorySize: 1024
      Timeout: 300
      DeadLetterQueue:
        Type: SQS
        TargetArn: !GetAtt DLQReplayDLQ.Arn
      Policies:
        - Statement:
          - Effect: Allow
            Action:
              - sqs:ReceiveMessage
              - sqs:DeleteMessage
              - sqs:ChangeMessageVisibility
            Resource: !Sub arn:aws:sqs:*:*:*-dlq*
          - Effect: Allow
            Action:
              - sns:Publish
            Resource: !Sub arn:aws:sns:*:*:lmall-*
```

## Performance Optimization

### 1. Cold Start Mitigation

```java
// Use AWS Lambda SnapStart for Java
@Configuration
public class SnapStartConfiguration {

    @Bean
    public CRaCCheckpointListener checkpointListener() {
        return new CRaCCheckpointListener();
    }

    @PostConstruct
    public void primeConnections() {
        // Pre-initialize connections during snapshot
        DynamoDbClient.builder().build();
        SnsClient.builder().build();

        // Pre-load classes
        try {
            Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
            Class.forName("software.amazon.awssdk.services.sns.model.PublishRequest");
        } catch (ClassNotFoundException e) {
            // Ignore
        }
    }
}
```

### 2. GraalVM Native Image

```xml
<!-- pom.xml -->
<build>
  <plugins>
    <plugin>
      <groupId>org.graalvm.buildtools</groupId>
      <artifactId>native-maven-plugin</artifactId>
      <configuration>
        <imageName>lambda-native</imageName>
        <buildArgs>
          --no-fallback
          --initialize-at-build-time
          --enable-http
          --enable-https
        </buildArgs>
      </configuration>
    </plugin>
  </plugins>
</build>
```

## Monitoring and Alerting

### CloudWatch Dashboard

```json
{
  "widgets": [
    {
      "type": "metric",
      "properties": {
        "metrics": [
          ["AWS/Lambda", "Duration", {"stat": "Average"}],
          [".", ".", {"stat": "p99"}],
          [".", "Errors", {"stat": "Sum"}],
          [".", "Throttles", {"stat": "Sum"}],
          [".", "ConcurrentExecutions", {"stat": "Maximum"}],
          ["LMall/Lambda", "DLQReplaySuccess", {"stat": "Sum"}],
          [".", "DLQReplayFailed", {"stat": "Sum"}],
          [".", "OrdersCanceled", {"stat": "Sum"}]
        ],
        "period": 300,
        "stat": "Average",
        "region": "us-east-1",
        "title": "Lambda Functions Health"
      }
    }
  ]
}
```

### X-Ray Tracing

```java
@Component
public class TracingInterceptor {

    @Around("@annotation(traced)")
    public Object trace(ProceedingJoinPoint joinPoint, Traced traced) throws Throwable {
        Subsegment subsegment = AWSXRay.beginSubsegment(traced.value());

        try {
            Object result = joinPoint.proceed();
            subsegment.putMetadata("result", "success");
            return result;
        } catch (Exception e) {
            subsegment.addException(e);
            subsegment.putMetadata("error", e.getMessage());
            throw e;
        } finally {
            AWSXRay.endSubsegment();
        }
    }
}
```

## Testing

### Unit Tests

```java
@Test
public void testCancelScheduler() {
    // Given
    ScheduledEvent event = new ScheduledEvent();
    Context context = new TestContext();

    when(dynamoClient.query(any())).thenReturn(mockTimeoutResponse());
    when(snsClient.publish(any())).thenReturn(PublishResponse.builder()
        .messageId("test-message-id")
        .build());

    // When
    CancelSchedulerResponse response = handler.handleRequest(event, context);

    // Then
    assertEquals(1, response.getProcessedCount());
    assertEquals(0, response.getFailedCount());
    verify(snsClient, times(1)).publish(any());
}
```

### Integration Tests with LocalStack

```bash
# docker-compose.yml for local testing
version: '3.8'
services:
  localstack:
    image: localstack/localstack
    environment:
      - SERVICES=dynamodb,sns,sqs,lambda
      - DEBUG=1
      - LAMBDA_EXECUTOR=docker-reuse
    ports:
      - "4566:4566"
    volumes:
      - "./lambda:/opt/code/lambda"
      - "/var/run/docker.sock:/var/run/docker.sock"
```

## Deployment Pipeline

```yaml
# GitHub Actions workflow
name: Deploy Lambda Functions

on:
  push:
    branches: [main]
    paths:
      - 'lambda/**'

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2

      - name: Setup Java
        uses: actions/setup-java@v2
        with:
          java-version: '17'
          distribution: 'corretto'

      - name: Build Lambda JAR
        run: |
          cd lambda
          mvn clean package

      - name: Deploy with SAM
        run: |
          sam build
          sam deploy \
            --stack-name lmall-lambda \
            --s3-bucket ${{ secrets.DEPLOYMENT_BUCKET }} \
            --capabilities CAPABILITY_IAM \
            --parameter-overrides Stage=prod \
            --no-confirm-changeset
```