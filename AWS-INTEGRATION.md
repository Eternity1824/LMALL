# AWS Integration Architecture

## ADR-007: Event-driven on AWS — RPC for Hot Path, Lambda for Cold Path

### Context

- Adopt Outbox + Event-driven consistency using AWS SNS+SQS
- AWS Lambda cold starts introduce latency, especially in VPC or high-concurrency scenarios
- Target: Order processing end-to-end P99 ≤ 300ms (excluding payment flow)
- Cancellation/compensation tasks allow multi-second latency

### Decision

#### Hot Path (Real-time) via ECS

- **OrderService**, **InventoryService**, **PaymentWebhook** deployed on ECS Fargate
- Long-lived RPC interfaces for stable performance
- Local transactions: Write business data + outbox in same transaction

#### Cold Path (Non-realtime) via Lambda

- **CancelScheduler**: EventBridge + Lambda triggers OrderCancelRequested
- **DLQ Replay**: Lambda processes SQS DLQ with idempotent replay
- **Cache Prewarm**: Lambda batch prewarming Redis hot keys post-deployment
- **SQS Consumers**: Default ECS processing, Lambda for tail work and tools

### Architecture Overview

```
[Client] -> [ALB/WAF] -> [ECS: OrderService] -> [RDS Aurora]
                              |
                              v
                        [Outbox Table]
                              |
                    [Publisher -> SNS Topics]
                              |
         ------------------------------------------------
         |                       |                     |
 [SQS: Inventory.*]       [SQS: Order.*]       [SQS: Payment.*]
         |                       |                     |
 [ECS: InventoryConsumer] [ECS: OrderConsumer] [ECS: PaymentConsumer]
         |                       |                     |
   [Redis TTL Holds]       State Machine          State Machine
         |
   [EventBridge] -> [Lambda: CancelScheduler] -> SNS
                                 |
                           [SQS Cancel.*]

[DLQ.*] -> [Lambda: DLQ Replay -> SNS]
```

## AWS Services Configuration

### SNS Topics (FIFO)

```yaml
Topics:
  OrderEvents:
    Name: lmall-order-events.fifo
    Type: FIFO
    ContentBasedDeduplication: true
    MessageRetentionPeriod: 14 # days
    Events:
      - OrderCreated
      - OrderCancelRequested
      - OrderConfirmed
      - OrderCanceled

  PaymentEvents:
    Name: lmall-payment-events.fifo
    Type: FIFO
    ContentBasedDeduplication: true
    Events:
      - PaymentCaptured
      - PaymentFailed

  InventoryEvents:
    Name: lmall-inventory-events
    Type: Standard  # Non-strict ordering
    Events:
      - InventoryReserved
      - InventoryReleased
      - InventorySold
```

### SQS Queues Configuration

```yaml
Queues:
  # Order Service Queues
  order-payment-captured:
    Type: FIFO
    MessageGroupId: orderId
    MessageDeduplicationId: outbox.id
    VisibilityTimeout: 30
    MaxReceiveCount: 5
    DLQ: order-payment-captured-dlq.fifo

  order-inventory-released:
    Type: Standard
    VisibilityTimeout: 30
    MaxReceiveCount: 5
    DLQ: order-inventory-released-dlq

  # Inventory Service Queues
  inventory-order-created:
    Type: FIFO
    MessageGroupId: orderId
    VisibilityTimeout: 45
    MaxReceiveCount: 5
    DLQ: inventory-order-created-dlq.fifo

  inventory-order-cancel-requested:
    Type: FIFO
    MessageGroupId: orderId
    VisibilityTimeout: 30
    MaxReceiveCount: 5
    DLQ: inventory-order-cancel-requested-dlq.fifo
```

### ECS Fargate Configuration

```yaml
Services:
  OrderService:
    TaskDefinition:
      CPU: 1024  # 1 vCPU
      Memory: 2048  # 2 GB
      ContainerDefinitions:
        - Name: order-service
          Image: lmall/order-service:latest
          Environment:
            - SPRING_PROFILES_ACTIVE=aws
            - AWS_REGION=us-east-1
          Secrets:
            - Name: DB_PASSWORD
              ValueFrom: arn:aws:secretsmanager:region:account:secret:rds-password

    Service:
      DesiredCount: 3
      MinimumHealthyPercent: 100
      MaximumPercent: 200
      TargetGroup:
        HealthCheckPath: /actuator/health
        HealthCheckIntervalSeconds: 30

    AutoScaling:
      MinCapacity: 3
      MaxCapacity: 20
      TargetTrackingScaling:
        - Type: ECSServiceAverageCPUUtilization
          TargetValue: 70
        - Type: ALBRequestCountPerTarget
          TargetValue: 1000
```

## Java Implementation for AWS

### SNS Publisher with Outbox

```java
@Component
public class SNSOutboxPublisher {

    private final SnsClient snsClient;
    private final OutboxRepository outboxRepository;

    @Value("${aws.sns.topic.prefix}")
    private String topicPrefix;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxRepository
            .findTop100ByStatusOrderByCreatedAt("PENDING");

        for (OutboxEvent event : pendingEvents) {
            try {
                String topicArn = getTopicArn(event.getEventType());

                PublishRequest request = PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(event.getPayload())
                    .messageGroupId(event.getAggregateId())  // For FIFO
                    .messageDeduplicationId(event.getId().toString())
                    .messageAttributes(Map.of(
                        "event_type", MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(event.getEventType())
                            .build(),
                        "aggregate_id", MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(event.getAggregateId())
                            .build()
                    ))
                    .build();

                PublishResponse response = snsClient.publish(request);

                event.setStatus("SENT");
                event.setSentAt(LocalDateTime.now());
                event.setMessageId(response.messageId());

                log.debug("Published event {} to SNS: {}",
                    event.getId(), response.messageId());

            } catch (SnsException e) {
                handlePublishError(event, e);
            }

            outboxRepository.save(event);
        }
    }

    private String getTopicArn(String eventType) {
        // Map event type to SNS topic ARN
        String topicName = eventType.split("\\.")[0]; // e.g., "order" from "order.created"
        return String.format("arn:aws:sns:%s:%s:%s-%s-events.fifo",
            System.getenv("AWS_REGION"),
            System.getenv("AWS_ACCOUNT_ID"),
            topicPrefix,
            topicName
        );
    }
}
```

### SQS Consumer with Idempotency

```java
@Component
@Slf4j
public class SQSIdempotentConsumer {

    private final SqsClient sqsClient;
    private final ConsumerDedupeRepository dedupeRepository;

    @Value("${aws.sqs.queue.url}")
    private String queueUrl;

    @SqsListener(value = "${aws.sqs.queue.name}", deletionPolicy = SqsMessageDeletionPolicy.ON_SUCCESS)
    @Transactional
    public void processMessage(@Payload String messageBody,
                              @Headers Map<String, String> headers,
                              Acknowledgment acknowledgment) {

        String messageId = headers.get("MessageId");
        String consumerName = this.getClass().getSimpleName();

        // Check idempotency
        if (dedupeRepository.existsByConsumerNameAndMessageId(consumerName, messageId)) {
            log.info("Duplicate message ignored: {}", messageId);
            acknowledgment.acknowledge();
            return;
        }

        try {
            // Parse message
            EventMessage event = parseMessage(messageBody);

            // Process business logic
            processEvent(event);

            // Mark as processed
            dedupeRepository.save(new ConsumerDedupe(consumerName, messageId));

            // Acknowledge message
            acknowledgment.acknowledge();

            log.info("Processed message {} successfully", messageId);

        } catch (Exception e) {
            log.error("Failed to process message {}", messageId, e);
            throw e; // Let SQS retry mechanism handle it
        }
    }

    protected abstract void processEvent(EventMessage event);
}
```

### Lambda Functions

#### Cancel Scheduler Lambda

```java
public class CancelSchedulerHandler implements RequestHandler<ScheduledEvent, Void> {

    private final DynamoDbClient dynamoClient;
    private final SnsClient snsClient;

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        LambdaLogger logger = context.getLogger();

        // Query expired timeouts from DynamoDB
        QueryRequest queryRequest = QueryRequest.builder()
            .tableName("order-timeouts")
            .indexName("timeout-index")
            .keyConditionExpression("timeout_at < :now AND processed = :false")
            .expressionAttributeValues(Map.of(
                ":now", AttributeValue.builder().n(String.valueOf(Instant.now().toEpochMilli())).build(),
                ":false", AttributeValue.builder().bool(false).build()
            ))
            .limit(100)
            .build();

        QueryResponse response = dynamoClient.query(queryRequest);

        for (Map<String, AttributeValue> item : response.items()) {
            String orderId = item.get("order_id").s();

            try {
                // Publish cancellation request
                PublishRequest publishRequest = PublishRequest.builder()
                    .topicArn(System.getenv("ORDER_EVENTS_TOPIC_ARN"))
                    .message(buildCancelRequestMessage(orderId))
                    .messageGroupId(orderId)
                    .messageDeduplicationId(UUID.randomUUID().toString())
                    .messageAttributes(Map.of(
                        "event_type", MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue("order.cancel.requested")
                            .build()
                    ))
                    .build();

                snsClient.publish(publishRequest);

                // Mark as processed
                updateTimeoutAsProcessed(orderId);

                logger.log("Published cancel request for order: " + orderId);

            } catch (Exception e) {
                logger.log("Failed to process timeout for order " + orderId + ": " + e.getMessage());
            }
        }

        return null;
    }
}
```

#### DLQ Replay Lambda

```java
public class DLQReplayHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private final SnsClient snsClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<BatchItemFailure> failures = new ArrayList<>();

        for (SQSMessage message : event.getRecords()) {
            try {
                // Parse original message
                Map<String, Object> originalMessage = objectMapper.readValue(
                    message.getBody(),
                    new TypeReference<Map<String, Object>>() {}
                );

                // Extract event details
                String eventType = (String) originalMessage.get("event_type");
                String aggregateId = (String) originalMessage.get("aggregate_id");
                String payload = objectMapper.writeValueAsString(originalMessage.get("payload"));

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
                attributes.put("replay_time", MessageAttributeValue.builder()
                    .dataType("Number")
                    .stringValue(String.valueOf(System.currentTimeMillis()))
                    .build());
                attributes.put("original_message_id", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(message.getMessageId())
                    .build());

                // Republish to SNS
                PublishRequest request = PublishRequest.builder()
                    .topicArn(getTopicArn(eventType))
                    .message(payload)
                    .messageGroupId(aggregateId)
                    .messageDeduplicationId(UUID.randomUUID().toString())
                    .messageAttributes(attributes)
                    .build();

                snsClient.publish(request);

                context.getLogger().log("Replayed DLQ message: " + message.getMessageId());

            } catch (Exception e) {
                context.getLogger().log("Failed to replay message " +
                    message.getMessageId() + ": " + e.getMessage());

                failures.add(BatchItemFailure.builder()
                    .itemIdentifier(message.getMessageId())
                    .build());
            }
        }

        return SQSBatchResponse.builder()
            .batchItemFailures(failures)
            .build();
    }
}
```

### EventBridge Configuration

```yaml
Rules:
  OrderTimeoutScanner:
    Name: order-timeout-scanner
    Description: Scan for expired order payment timeouts
    ScheduleExpression: rate(1 minute)
    State: ENABLED
    Targets:
      - Arn: arn:aws:lambda:region:account:function:cancel-scheduler
        RoleArn: arn:aws:iam::account:role/eventbridge-lambda-role
        RetryPolicy:
          MaximumRetryAttempts: 2
          MaximumEventAge: 3600

  DLQReplaySchedule:
    Name: dlq-replay-schedule
    Description: Periodic DLQ replay for failed messages
    ScheduleExpression: rate(15 minutes)
    State: ENABLED
    Targets:
      - Arn: arn:aws:lambda:region:account:function:dlq-replay
        Input: |
          {
            "queues": [
              "order-payment-captured-dlq.fifo",
              "inventory-order-created-dlq.fifo"
            ],
            "max_messages": 100
          }
```

### Spring Boot AWS Configuration

```yaml
# application-aws.yml
spring:
  cloud:
    aws:
      region:
        static: us-east-1
      credentials:
        instance-profile: true  # Use EC2 instance role

aws:
  sns:
    topic:
      prefix: lmall
      order-events: ${AWS_ACCOUNT_ID}/lmall-order-events.fifo
      payment-events: ${AWS_ACCOUNT_ID}/lmall-payment-events.fifo
      inventory-events: ${AWS_ACCOUNT_ID}/lmall-inventory-events

  sqs:
    queues:
      order-payment-captured: https://sqs.us-east-1.amazonaws.com/${AWS_ACCOUNT_ID}/order-payment-captured.fifo
      inventory-order-created: https://sqs.us-east-1.amazonaws.com/${AWS_ACCOUNT_ID}/inventory-order-created.fifo

cloud:
  aws:
    sqs:
      listener:
        max-number-of-messages: 10
        wait-time-out: 20  # Long polling
        visibility-timeout: 30
        auto-startup: true
```

### Infrastructure as Code (CDK)

```java
public class LMallStack extends Stack {

    public LMallStack(final Construct scope, final String id) {
        super(scope, id);

        // Create FIFO SNS Topics
        Topic orderEventsTopic = Topic.Builder.create(this, "OrderEventsTopic")
            .topicName("lmall-order-events.fifo")
            .fifo(true)
            .contentBasedDeduplication(true)
            .build();

        // Create SQS Queues with DLQ
        Queue orderPaymentCapturedDLQ = Queue.Builder.create(this, "OrderPaymentCapturedDLQ")
            .queueName("order-payment-captured-dlq.fifo")
            .fifo(true)
            .retentionPeriod(Duration.days(14))
            .build();

        Queue orderPaymentCapturedQueue = Queue.Builder.create(this, "OrderPaymentCapturedQueue")
            .queueName("order-payment-captured.fifo")
            .fifo(true)
            .visibilityTimeout(Duration.seconds(30))
            .deadLetterQueue(DeadLetterQueue.builder()
                .queue(orderPaymentCapturedDLQ)
                .maxReceiveCount(5)
                .build())
            .build();

        // Subscribe Queue to Topic
        orderEventsTopic.addSubscription(new SqsSubscription(orderPaymentCapturedQueue,
            SqsSubscriptionProps.builder()
                .filterPolicy(Map.of(
                    "event_type", SubscriptionFilter.stringFilter(
                        StringConditions.builder()
                            .allowlist(List.of("payment.captured"))
                            .build()
                    )
                ))
                .build()
        ));

        // ECS Cluster
        Cluster cluster = Cluster.Builder.create(this, "LMallCluster")
            .clusterName("lmall-cluster")
            .containerInsights(true)
            .build();

        // Fargate Service
        ApplicationLoadBalancedFargateService orderService =
            ApplicationLoadBalancedFargateService.Builder.create(this, "OrderService")
                .cluster(cluster)
                .serviceName("order-service")
                .taskImageOptions(ApplicationLoadBalancedTaskImageOptions.builder()
                    .image(ContainerImage.fromRegistry("lmall/order-service:latest"))
                    .containerPort(8080)
                    .environment(Map.of(
                        "SPRING_PROFILES_ACTIVE", "aws",
                        "AWS_REGION", this.getRegion()
                    ))
                    .build())
                .memoryLimitMiB(2048)
                .cpu(1024)
                .desiredCount(3)
                .build();

        // Auto Scaling
        ScalableTaskCount scalableTarget = orderService.getService().autoScaleTaskCount(
            EnableScalingProps.builder()
                .minCapacity(3)
                .maxCapacity(20)
                .build()
        );

        scalableTarget.scaleOnCpuUtilization("CpuScaling",
            CpuUtilizationScalingProps.builder()
                .targetUtilizationPercent(70)
                .build()
        );

        // Lambda Functions
        Function cancelScheduler = Function.Builder.create(this, "CancelScheduler")
            .functionName("order-cancel-scheduler")
            .runtime(Runtime.JAVA_17)
            .code(Code.fromAsset("../lambda/target/lambda.jar"))
            .handler("com.lmall.lambda.CancelSchedulerHandler::handleRequest")
            .timeout(Duration.minutes(5))
            .memorySize(512)
            .environment(Map.of(
                "ORDER_EVENTS_TOPIC_ARN", orderEventsTopic.getTopicArn()
            ))
            .build();

        // Grant permissions
        orderEventsTopic.grantPublish(cancelScheduler);

        // EventBridge Rule
        Rule timeoutRule = Rule.Builder.create(this, "TimeoutRule")
            .ruleName("order-timeout-scanner")
            .schedule(Schedule.rate(Duration.minutes(1)))
            .build();

        timeoutRule.addTarget(new LambdaFunction(cancelScheduler));
    }
}
```

## Monitoring and Observability

### CloudWatch Dashboards

```json
{
  "name": "LMall-Operations",
  "widgets": [
    {
      "type": "metric",
      "properties": {
        "metrics": [
          ["AWS/SQS", "ApproximateNumberOfMessagesVisible", {"stat": "Average"}],
          [".", "ApproximateAgeOfOldestMessage", {"stat": "Maximum"}],
          ["AWS/SNS", "NumberOfMessagesPublished", {"stat": "Sum"}],
          ["AWS/Lambda", "Duration", {"stat": "Average"}],
          [".", "Errors", {"stat": "Sum"}],
          ["AWS/ECS", "CPUUtilization", {"stat": "Average"}]
        ],
        "period": 300,
        "stat": "Average",
        "region": "us-east-1",
        "title": "System Health"
      }
    }
  ]
}
```

### CloudWatch Alarms

```yaml
Alarms:
  - Name: high-queue-backlog
    MetricName: ApproximateNumberOfMessagesVisible
    Namespace: AWS/SQS
    Dimensions:
      - Name: QueueName
        Value: order-payment-captured.fifo
    Statistic: Average
    Period: 300
    EvaluationPeriods: 2
    Threshold: 1000
    ComparisonOperator: GreaterThanThreshold
    AlarmActions:
      - !Ref SNSAlertTopic

  - Name: dlq-messages-present
    MetricName: ApproximateNumberOfMessagesVisible
    Namespace: AWS/SQS
    Dimensions:
      - Name: QueueName
        Value: order-payment-captured-dlq.fifo
    Statistic: Sum
    Period: 300
    EvaluationPeriods: 1
    Threshold: 0
    ComparisonOperator: GreaterThanThreshold
    AlarmActions:
      - !Ref SNSAlertTopic

  - Name: lambda-errors-high
    MetricName: Errors
    Namespace: AWS/Lambda
    Dimensions:
      - Name: FunctionName
        Value: order-cancel-scheduler
    Statistic: Sum
    Period: 300
    EvaluationPeriods: 2
    Threshold: 5
    ComparisonOperator: GreaterThanThreshold
```

## Cost Optimization

### Strategies

1. **Reserved Capacity**:
   - Reserve Fargate capacity for baseline load
   - Use spot instances for non-critical workloads

2. **SQS Long Polling**:
   - Reduce API calls with 20-second wait time

3. **Lambda Optimization**:
   - Right-size memory allocation
   - Use ARM-based Graviton2 for Lambda

4. **S3 Lifecycle Policies**:
   - Archive old DLQ messages to Glacier
   - Delete processed outbox entries after 30 days

5. **DynamoDB On-Demand**:
   - Use on-demand billing for variable workloads
   - Consider reserved capacity for predictable patterns

### Cost Estimates

```yaml
Monthly Costs (Est.):
  ECS Fargate:
    - 3 tasks × 1 vCPU × 2GB × 24h × 30d = $108
    - With reserved: ~$75

  SNS:
    - 10M messages × $0.50/million = $5

  SQS:
    - 10M requests × $0.40/million = $4
    - FIFO: additional $0.10/million = $1

  Lambda:
    - 100K invocations × 512MB × 1s avg = $2

  CloudWatch:
    - Logs: 50GB × $0.50/GB = $25
    - Metrics: Custom metrics = $10

  Total: ~$130/month (baseline)
```

## Migration Strategy

### Phase 1: Shadow Mode (Week 1-2)
- Deploy AWS infrastructure alongside existing RabbitMQ
- Dual publish to both RabbitMQ and SNS
- Shadow consumers on SQS (no side effects)
- Monitor metrics parity

### Phase 2: Non-Critical Migration (Week 3-4)
- Migrate analytics and reporting consumers to SQS
- Keep critical path on RabbitMQ
- Validate message ordering and deduplication

### Phase 3: Critical Path Migration (Week 5-6)
- Migrate inventory and order consumers to SQS
- Implement circuit breaker for rollback
- Load test with 10x normal traffic

### Phase 4: Cleanup (Week 7)
- Decommission RabbitMQ infrastructure
- Remove dual publishing code
- Update documentation and runbooks

### Rollback Plan
1. Circuit breaker triggers on error rate > 1%
2. Revert DNS/ALB routing to previous version
3. Restore RabbitMQ consumers from backup
4. Analyze failure logs and metrics
5. Fix issues before retry

## Security Considerations

### IAM Roles and Policies

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ecs-tasks.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
```

### ECS Task Role Policy

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "sns:Publish"
      ],
      "Resource": [
        "arn:aws:sns:*:*:lmall-*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes"
      ],
      "Resource": [
        "arn:aws:sqs:*:*:order-*",
        "arn:aws:sqs:*:*:inventory-*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue"
      ],
      "Resource": [
        "arn:aws:secretsmanager:*:*:secret:lmall/*"
      ]
    }
  ]
}
```

### VPC and Network Security

```yaml
VPC:
  CIDR: 10.0.0.0/16

  PrivateSubnets:
    - 10.0.1.0/24  # ECS Tasks
    - 10.0.2.0/24  # RDS
    - 10.0.3.0/24  # ElastiCache

  PublicSubnets:
    - 10.0.101.0/24  # ALB
    - 10.0.102.0/24  # NAT Gateway

SecurityGroups:
  ECS:
    Ingress:
      - Port: 8080
        Source: ALB Security Group
    Egress:
      - Port: 443
        Destination: 0.0.0.0/0  # AWS APIs
      - Port: 3306
        Destination: RDS Security Group
      - Port: 6379
        Destination: Redis Security Group
```

## Operational Runbooks

### Incident Response

1. **High Queue Backlog**
   - Scale ECS tasks horizontally
   - Check consumer error logs
   - Verify database connections
   - Consider temporary rate limiting

2. **DLQ Messages Accumulating**
   - Analyze message patterns for common errors
   - Run manual DLQ replay Lambda
   - Fix root cause before bulk replay
   - Consider dead letter analysis tool

3. **Lambda Cold Start Issues**
   - Enable provisioned concurrency
   - Optimize Lambda package size
   - Consider moving to ECS for consistent latency

4. **Database Connection Exhaustion**
   - Implement connection pooling
   - Scale RDS read replicas
   - Review query optimization
   - Consider RDS Proxy

## Performance Benchmarks

### Target SLAs

```yaml
Metrics:
  Order Creation:
    P50: < 50ms
    P99: < 300ms
    Success Rate: > 99.9%

  Event Publishing:
    Latency: < 100ms
    Throughput: > 1000 msg/s

  Message Processing:
    End-to-End: < 5s
    DLQ Rate: < 0.1%

  System Availability:
    Uptime: 99.95%
    RTO: < 15 minutes
    RPO: < 1 minute
```

### Load Testing

```bash
# Artillery.io configuration
config:
  target: "https://api.lmall.com"
  phases:
    - duration: 60
      arrivalRate: 10
      name: "Warm up"
    - duration: 300
      arrivalRate: 100
      name: "Sustained load"
    - duration: 120
      arrivalRate: 500
      name: "Peak load"

scenarios:
  - name: "Create Order"
    flow:
      - post:
          url: "/api/orders"
          json:
            user_id: "{{ $randomString() }}"
            items:
              - sku: "ITEM-{{ $randomNumber(1, 1000) }}"
                quantity: "{{ $randomNumber(1, 5) }}"
                price: "{{ $randomNumber(10, 100) }}"
```

## Future Enhancements

1. **Multi-Region Active-Active**
   - Global event replication
   - Cross-region failover
   - Geo-distributed caching

2. **Event Streaming with Kinesis**
   - Real-time analytics
   - Event replay from stream
   - Longer retention period

3. **Step Functions for Orchestration**
   - Complex order workflows
   - Visual workflow designer
   - Built-in error handling

4. **API Gateway Integration**
   - Rate limiting at edge
   - Request/response transformation
   - API versioning

5. **Machine Learning Integration**
   - Fraud detection with SageMaker
   - Demand forecasting
   - Personalized recommendations