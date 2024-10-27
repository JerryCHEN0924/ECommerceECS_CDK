package com.myorg;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.sns.StringConditions;
import software.amazon.awscdk.services.sns.SubscriptionFilter;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.SqsSubscription;
import software.amazon.awscdk.services.sns.subscriptions.SqsSubscriptionProps;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.sqs.QueueEncryption;
import software.amazon.awscdk.services.sqs.QueueProps;
import software.constructs.Construct;

import java.util.*;

public class AuditServiceStack extends Stack {
    public AuditServiceStack(final Construct scope, final String id,
                             final StackProps props, AuditServiceProps auditServiceProps) {
        super(scope, id, props);
        //建立events Dynamo DB 並使用複合主鍵
        Table eventsDdb = new Table(this, "EventsDdb", TableProps.builder()
                .tableName("events")
                .removalPolicy(RemovalPolicy.DESTROY)
                .partitionKey(Attribute.builder()
                        .name("pk")
                        .type(AttributeType.STRING)
                        .build())
                .sortKey(Attribute.builder()
                        .name("sk")
                        .type(AttributeType.STRING)
                        .build())
                .timeToLiveAttribute("ttl")
                .billingMode(BillingMode.PROVISIONED)
                .readCapacity(1)
                .writeCapacity(1)
                .build());

        //建立AWS SQS
        Queue productEventsDlq = new Queue(this, "ProductEventsDlq",
                QueueProps.builder()
                        .queueName("product-events-dlq")
                        .retentionPeriod(Duration.days(10))//保留多久
                        .build());

        Queue productEventsQueue = new Queue(this, "ProductEventsQueue",
                QueueProps.builder()
                        .queueName("product-events")
                        .enforceSsl(false)
                        .encryption(QueueEncryption.UNENCRYPTED)
                        .deadLetterQueue(DeadLetterQueue.builder()
                                .queue(productEventsDlq) //配置DLQ
                                .maxReceiveCount(3) //拋出三次異常就把此訊息放進DLQ中
                                .build())
                        .build());

        Map<String, SubscriptionFilter> productsFilterPolicy = new HashMap<>();
        productsFilterPolicy.put(
                "eventType", SubscriptionFilter.stringFilter(StringConditions.builder()
                        .allowlist(Arrays.asList("PRODUCT_CREATED", "PRODUCT_UPDATED", "PRODUCT_DELETED"))
                        .build())
        );

        auditServiceProps.productEventsTopic().addSubscription(new SqsSubscription(productEventsQueue,
                SqsSubscriptionProps.builder()
                        .filterPolicy(productsFilterPolicy)
                        .build()));

        Queue productFailureEventsQueue = new Queue(this, "ProductFailureEventsQueue",
                QueueProps.builder()
                        .queueName("product-failure-events")
                        .enforceSsl(false)
                        .encryption(QueueEncryption.UNENCRYPTED)
                        .deadLetterQueue(DeadLetterQueue.builder()
                                .queue(productEventsDlq) //配置DLQ
                                .maxReceiveCount(3) //拋出三次異常就把此訊息放進DLQ中
                                .build())
                        .build());

        Map<String, SubscriptionFilter> productsFailureFilterPolicy = new HashMap<>();
        productsFailureFilterPolicy.put(
                "eventType", SubscriptionFilter.stringFilter(StringConditions.builder()
                        .allowlist(Collections.singletonList("PRODUCT_FAILURE"))
                        .build())
        );

        auditServiceProps.productEventsTopic().addSubscription(new SqsSubscription(productFailureEventsQueue,
                SqsSubscriptionProps.builder()
                        .filterPolicy(productsFailureFilterPolicy)
                        .build()));

        //Fargate 是一種無伺服器容器運行方式，讓用戶不需要管理底層伺服器基礎設施，專注於容器的運行和管理。
        FargateTaskDefinition fargateTaskDefinition = new FargateTaskDefinition(this, "TaskDefinition",
                FargateTaskDefinitionProps.builder()
                        .family("audit-service")
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .build());
        fargateTaskDefinition.getTaskRole().addManagedPolicy(
                ManagedPolicy.fromAwsManagedPolicyName("AWSXrayWriteOnlyAccess"));
        productEventsQueue.grantConsumeMessages(fargateTaskDefinition.getTaskRole());
        productFailureEventsQueue.grantConsumeMessages(fargateTaskDefinition.getTaskRole());
        eventsDdb.grantReadWriteData(fargateTaskDefinition.getTaskRole());

        AwsLogDriver awsLogDriver = new AwsLogDriver(AwsLogDriverProps.builder()
                .logGroup(new LogGroup(this, "LogGroup", LogGroupProps.builder() //LogGroup可以理解為資料夾
                        .logGroupName("AuditService")
                        .removalPolicy(RemovalPolicy.DESTROY) //移除方案，如果刪除這個Stack，那此資源也會跟著被刪除。
                        .retention(RetentionDays.ONE_MONTH) //保留日誌的時間長度
                        .build()))
                .streamPrefix("AuditService")//文件前綴名稱
                .build());

        //傳遞到應用程式中的環境變數
        Map<String, String> envVariables = new HashMap<>();
        envVariables.put("Server_PORT", "9090");
        envVariables.put("AWS_REGION", this.getRegion());
        envVariables.put("AWS_XRAY_DAEMON_ADDRESS", "0.0.0.0:2000");
        envVariables.put("AWS_XRAY_CONTEXT_MISSING", "IGNORE_ERROR");
        envVariables.put("AWS_XRAY_TRACING_NAME", "auditservice");
        envVariables.put("AWS_SQS_QUEUE_PRODUCT_EVENTS_URL", productEventsQueue.getQueueUrl());
        envVariables.put("AWS_SQS_QUEUE_PRODUCT_FAILURE_EVENTS_URL", productFailureEventsQueue.getQueueUrl());
        envVariables.put("AWS_EVENTS_DDB", eventsDdb.getTableName());
        envVariables.put("LOGGING_LEVEL_ROOT", "INFO");

        fargateTaskDefinition.addContainer("AuditServiceContainer",
                ContainerDefinitionOptions.builder()
                        //定義image映像位置與版本號，此範例中是使用存放於AWS ECR中的Image。
                        .image(ContainerImage.fromEcrRepository(auditServiceProps.repository(), "1.4.0"))
                        .containerName("auditService")
                        .logging(awsLogDriver) //將log儲存到CloudWatch
                        .portMappings(Collections.singletonList(PortMapping.builder()
                                .containerPort(9090)
                                .protocol(Protocol.TCP)
                                .build()))
                        .environment(envVariables) //這個環境變數會傳遞到AuditService應用程式中，作為port或其他變數使用。
                        .cpu(384)
                        .memoryLimitMiB(896)
                        .build()); //新增一個容器

//為AWS X-Ray單獨分配一個容器，不應該把AWS X-Ray放入其他容器，會造成爭奪資源的情況。
        fargateTaskDefinition.addContainer("xray", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("public.ecr.aws/xray/aws-xray-dae    mon:latest"))
                .containerName("XRayAuditService")
                .logging(new AwsLogDriver(AwsLogDriverProps.builder()
                        .logGroup(new LogGroup(this, "XRayLogGroup", LogGroupProps.builder()
                                .logGroupName("XRayAuditService")
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .retention(RetentionDays.ONE_MONTH)
                                .build()))
                        .streamPrefix("XRayAuditService")
                        .build()))
                .portMappings(Collections.singletonList(PortMapping.builder()
                        .containerPort(2000)
                        .protocol(Protocol.UDP)
                        .build()))
                .cpu(128)
                .memoryLimitMiB(128)
                .build());

        //建立應用程式監聽器
        ApplicationListener applicationListener = auditServiceProps.applicationLoadBalancer()
                .addListener("AuditServiceAlbListener", ApplicationListenerProps.builder()
                        .port(9090)
                        .protocol(ApplicationProtocol.HTTP)
                        .loadBalancer(auditServiceProps.applicationLoadBalancer())
                        .build());

        //透過fargateTaskDefinition，建立Fargate。
        FargateService fargateService = new FargateService(this, "AuditService",
                FargateServiceProps.builder()
                        .serviceName("AuditService")
                        .cluster(auditServiceProps.cluster())
                        .taskDefinition(fargateTaskDefinition)
                        .desiredCount(2) //欲建立的實例數量
                        //DO NOT DO THIS IN PROD!!
                        .assignPublicIp(true) //會這樣設定是因為在VPC中，我們選擇不建立natGateways(擁有自己的IP)
                        .build());

        /*
        所有在AWS中的資源，即便都屬於你，但彼此溝通還是必須設定開通權限。
        所以要設定可以去ECR取得image
         */
        auditServiceProps.repository().grantPull(Objects.requireNonNull(fargateTaskDefinition.getExecutionRole()));

        //接受任何來自IP位址的任何內容，且在服務中設定TCP 9090 port來接收通訊傳入的請求。
        fargateService.getConnections().getSecurityGroups().get(0).addIngressRule(Peer.ipv4(auditServiceProps
                .vpc().getVpcCidrBlock()), Port.tcp(9090));

        //建立監聽器目標:，確認健康狀態。
        applicationListener.addTargets("AuditServiceAlbTarget",
                AddApplicationTargetsProps.builder()
                        .targetGroupName("auditServiceAlb")
                        .port(9090)
                        .protocol(ApplicationProtocol.HTTP)
                        .targets(Collections.singletonList(fargateService))
                        .deregistrationDelay(Duration.seconds(30))
                        .healthCheck(HealthCheck.builder()
                                .enabled(true)
                                .interval(Duration.seconds(30)) //每30秒發送一次健康檢查請求，確認實例健康狀態。
                                .timeout(Duration.seconds(10)) //超過10秒沒回應為逾時。
                                .path("/actuator/health") //SpringBoot中我們有引入此套件，負責確認應用程式健康狀態。
                                .port("9090")
                                .build())
                        .build()
        );

        //建立NetworkListener
        NetworkListener networkListener = auditServiceProps.networkLoadBalancer()
                .addListener("AuditServiceNlbListener", BaseNetworkListenerProps.builder()
                        .port(9090)
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
                        .build());

        networkListener.addTargets("AuditServiceNlbTarget",
                AddNetworkTargetsProps.builder()
                        .port(9090)
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
                        .targetGroupName("auditServiceNlb")
                        .targets(Collections.singletonList(
                                fargateService.loadBalancerTarget(LoadBalancerTargetOptions.builder()
                                        .containerName("auditService")
                                        .containerPort(9090)
                                        .protocol(Protocol.TCP)
                                        .build())))
                        .build());

    }
}

record AuditServiceProps(
        Vpc vpc,
        Cluster cluster,
        NetworkLoadBalancer networkLoadBalancer,
        ApplicationLoadBalancer applicationLoadBalancer,
        Repository repository,
        Topic productEventsTopic
) {
}