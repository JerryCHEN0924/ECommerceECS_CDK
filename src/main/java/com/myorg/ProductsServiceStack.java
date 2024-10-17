/**
 * ECS Stack TaskDefinition任務定義，設定CPU與記憶體大小與容器內的LOG存放位置。
 * LOG的存放會透過CloudWatch這個AWS的聚合服日誌服務建立。
 */
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
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.TopicProps;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscription;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscriptionProps;
import software.constructs.Construct;

import java.util.*;

public class ProductsServiceStack extends Stack {
    private final Topic productsEventsTopic;

    public ProductsServiceStack(final Construct scope, final String id, final StackProps props, ProductsServiceProps productsServicePros) {
        super(scope, id, props);

        //Topic
        this.productsEventsTopic = new Topic(this, "ProductEventsTopic", TopicProps.builder()
                .displayName("Product events topic")
                .topicName("products-events")
                .build());

        //TODO - to removed feature
        this.productsEventsTopic.addSubscription(new EmailSubscription("jk2455892@gmail.com",
                EmailSubscriptionProps.builder()
                        .json(true)
                        .build()
        ));

        //Dynamo DB
        Table productDdb = new Table(this, "ProductsDdb", TableProps.builder()
                .partitionKey(Attribute.builder()
                        .name("id")
                        .type(AttributeType.STRING)
                        .build())//Ddb中定義PK Key的屬性名稱
                .tableName("products")
                .removalPolicy(RemovalPolicy.DESTROY) //注意Dynamo DB的預設刪除政策是保留(RETAIN)，因為跟資料的儲存有關係。
                .billingMode(BillingMode.PROVISIONED) //建立具有一定容量的表
                .readCapacity(1)
                .writeCapacity(1)
                .build());

        productDdb.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
                .indexName("codeIdx")
                .partitionKey(Attribute.builder()
                        .name("code")
                        .type(AttributeType.STRING)
                        .build())
                .projectionType(ProjectionType.KEYS_ONLY)
                .readCapacity(1)
                .writeCapacity(1)
                .build());

        //Fargate 是一種無伺服器容器運行方式，讓用戶不需要管理底層伺服器基礎設施，專注於容器的運行和管理。
        FargateTaskDefinition fargateTaskDefinition = new FargateTaskDefinition(this, "TaskDefinition", FargateTaskDefinitionProps.builder()
                .family("products-service")
                .cpu(512)
                .memoryLimitMiB(1024)
                .build());

        //定義任務中的應用程式可以讀取與寫入數據
        productDdb.grantReadWriteData(fargateTaskDefinition.getTaskRole());
        this.productsEventsTopic.grantPublish(fargateTaskDefinition.getTaskRole());

        AwsLogDriver awsLogDriver = new AwsLogDriver(AwsLogDriverProps.builder()
                .logGroup(new LogGroup(this, "LogGroup", LogGroupProps.builder() //LogGroup可以理解為資料夾
                        .logGroupName("ProductsService")
                        .removalPolicy(RemovalPolicy.DESTROY) //移除方案，如果刪除這個Stack，那此資源也會跟著被刪除。
                        .retention(RetentionDays.ONE_MONTH) //保留日誌的時間長度
                        .build()))
                .streamPrefix("ProductsService")//文件前綴名稱
                .build());

        Map<String, String> envVariables = new HashMap<>();
        //傳遞到應用程式中的環境變數
        envVariables.put("Server_PORT", "8080");
        envVariables.put("AWS_PRODUCTSDDB_NAME", productDdb.getTableName());
        envVariables.put("AWS_SNS_TOPIC_PRODUCT_EVENTS", this.productsEventsTopic.getTopicArn());
        envVariables.put("AWS_REGION", this.getRegion());
        envVariables.put("AWS_XRAY_DAEMON_ADDRESS", "0.0.0.0:2000");
        envVariables.put("AWS_XRAY_CONTEXT_MISSING", "IGNORE_ERROR");
        envVariables.put("AWS_XRAY_TRACING_NAME", "productsservice");
        envVariables.put("LOGGING_LEVEL_ROOT", "INFO");

        fargateTaskDefinition.addContainer("ProductsServiceContainer",
                ContainerDefinitionOptions.builder()
                        //定義image映像位置與版本號，此範例中是使用存放於AWS ECR中的Image。
                        .image(ContainerImage.fromEcrRepository(productsServicePros.repository(), "1.8.0"))
                        .containerName("productsService")
                        .logging(awsLogDriver) //將log儲存到CloudWatch
                        .portMappings(Collections.singletonList(PortMapping.builder()
                                .containerPort(8080)
                                .protocol(Protocol.TCP)
                                .build()))
                        .environment(envVariables) //這個環境變數會傳遞到products應用程式中，作為port或其他變數使用。
                        .cpu(384)
                        .memoryLimitMiB(896)
                        .build()); //新增一個容器

        //為AWS X-Ray單獨分配一個容器，不應該把AWS X-Ray放入其他容器，會造成爭奪資源的情況。
        fargateTaskDefinition.addContainer("xray", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("public.ecr.aws/xray/aws-xray-dae    mon:latest"))
                .containerName("XRayProductsService")
                .logging(new AwsLogDriver(AwsLogDriverProps.builder()
                        .logGroup(new LogGroup(this, "XRayLogGroup", LogGroupProps.builder()
                                .logGroupName("XRayProductsService")
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .retention(RetentionDays.ONE_MONTH)
                                .build()))
                        .streamPrefix("XRayProductsService")
                        .build()))
                .portMappings(Collections.singletonList(PortMapping.builder()
                        .containerPort(2000)
                        .protocol(Protocol.UDP)
                        .build()))
                .cpu(128)
                .memoryLimitMiB(128)
                .build());
        fargateTaskDefinition.getTaskRole().addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AWSXrayWriteOnlyAccess"));

        //建立應用程式監聽器
        ApplicationListener applicationListener = productsServicePros.applicationLoadBalancer()
                .addListener("ProductsServiceAlbListener", ApplicationListenerProps.builder()
                        .port(8080)
                        .protocol(ApplicationProtocol.HTTP)
                        .loadBalancer(productsServicePros.applicationLoadBalancer())
                        .build());

        //透過fargateTaskDefinition，建立Fargate。
        FargateService fargateService = new FargateService(this, "ProductsService",
                FargateServiceProps.builder()
                        .serviceName("ProductsService")
                        .cluster(productsServicePros.cluster())
                        .taskDefinition(fargateTaskDefinition)
                        .desiredCount(2) //欲建立的實例數量
                        //DO NOT DO THIS IN PROD!!
                        .assignPublicIp(true) //會這樣設定是因為在VPC中，我們選擇不建立natGateways(擁有自己的IP)
                        .build());
        /*
        所有在AWS中的資源，即便都屬於你，但彼此溝通還是必須設定開通權限。
        所以要設定可以去ECR取得image
         */
        productsServicePros.repository().grantPull(Objects.requireNonNull(fargateTaskDefinition.getExecutionRole()));

        //接受任何來自IP位址的任何內容，且在服務中設定TCP 8080 port來接收通訊傳入的請求。
        fargateService.getConnections().getSecurityGroups().get(0).addIngressRule(Peer.anyIpv4(), Port.tcp(8080));

        //建立監聽器目標:，確認健康狀態。
        applicationListener.addTargets("ProductsServiceAlbTarget",
                AddApplicationTargetsProps.builder()
                        .targetGroupName("productsServiceAlb")
                        .port(8080)
                        .protocol(ApplicationProtocol.HTTP)
                        .targets(Collections.singletonList(fargateService))
                        .deregistrationDelay(Duration.seconds(30))
                        .healthCheck(HealthCheck.builder()
                                .enabled(true)
                                .interval(Duration.seconds(30)) //每30秒發送一次健康檢查請求，確認實例健康狀態。
                                .timeout(Duration.seconds(10)) //超過10秒沒回應為逾時。
                                .path("/actuator/health") //SpringBoot中我們有引入此套件，負責確認應用程式健康狀態。
                                .port("8080")
                                .build())
                        .build()
        );

        //建立NetworkListener
        NetworkListener networkListener = productsServicePros.networkLoadBalancer()
                .addListener("ProductsServiceNlbListener", BaseNetworkListenerProps.builder()
                        .port(8080)
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
                        .build());

        networkListener.addTargets("ProductServiceNlbTarget",
                AddNetworkTargetsProps.builder()
                        .port(8080)
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
                        .targetGroupName("productsServiceNlb")
                        .targets(Collections.singletonList(
                                fargateService.loadBalancerTarget(LoadBalancerTargetOptions.builder()
                                        .containerName("productsService")
                                        .containerPort(8080)
                                        .protocol(Protocol.TCP)
                                        .build())))
                        .build());
    }

    public Topic getProductsEventsTopic() {
        return productsEventsTopic;
    }
}

record ProductsServiceProps(
        Vpc vpc,
        Cluster cluster,
        NetworkLoadBalancer networkLoadBalancer,
        ApplicationLoadBalancer applicationLoadBalancer,
        Repository repository
) {
}
