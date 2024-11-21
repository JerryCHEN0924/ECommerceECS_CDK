package com.myorg;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.applicationautoscaling.EnableScalingProps;
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
import software.constructs.Construct;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class InvoicesServiceStack extends Stack {
    public InvoicesServiceStack(final Construct scope, final String id,
                                final StackProps props, InvoicesServiceProps invoicesServiceProps) {
        super(scope, id, props);
        //Fargate 是一種無伺服器容器運行方式，讓用戶不需要管理底層伺服器基礎設施，專注於容器的運行和管理。
        FargateTaskDefinition fargateTaskDefinition = new FargateTaskDefinition(this, "TaskDefinition",
                FargateTaskDefinitionProps.builder()
                        .family("invoices-service")
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .build());
        fargateTaskDefinition.getTaskRole().addManagedPolicy(
                ManagedPolicy.fromAwsManagedPolicyName("AWSXrayWriteOnlyAccess"));

        AwsLogDriver awsLogDriver = new AwsLogDriver(AwsLogDriverProps.builder()
                .logGroup(new LogGroup(this, "LogGroup", LogGroupProps.builder() //LogGroup可以理解為資料夾
                        .logGroupName("InvoicesService")
                        .removalPolicy(RemovalPolicy.DESTROY) //移除方案，如果刪除這個Stack，那此資源也會跟著被刪除。
                        .retention(RetentionDays.ONE_MONTH) //保留日誌的時間長度
                        .build()))
                .streamPrefix("InvoicesService")//文件前綴名稱
                .build());

        //傳遞到應用程式中的環境變數
        Map<String, String> envVariables = new HashMap<>();
        envVariables.put("Server_PORT", "9095");
        envVariables.put("AWS_REGION", this.getRegion());
        envVariables.put("AWS_XRAY_DAEMON_ADDRESS", "0.0.0.0:2000");
        envVariables.put("AWS_XRAY_CONTEXT_MISSING", "IGNORE_ERROR");
        envVariables.put("AWS_XRAY_TRACING_NAME", "invoicesService");
        envVariables.put("LOGGING_LEVEL_ROOT", "INFO");

        fargateTaskDefinition.addContainer("InvoicesServiceContainer",
                ContainerDefinitionOptions.builder()
                        //定義image映像位置與版本號，此範例中是使用存放於AWS ECR中的Image。
                        .image(ContainerImage.fromEcrRepository(invoicesServiceProps.repository(), "1.0.0"))
                        .containerName("invoicesService")
                        .logging(awsLogDriver) //將log儲存到CloudWatch
                        .portMappings(Collections.singletonList(PortMapping.builder()
                                .containerPort(9095)
                                .protocol(Protocol.TCP)
                                .build()))
                        .environment(envVariables) //這個環境變數會傳遞到InvoicesService應用程式中，作為port或其他變數使用。
                        .cpu(384)
                        .memoryLimitMiB(896)
                        .build()); //新增一個容器

        //為AWS X-Ray單獨分配一個容器，不應該把AWS X-Ray放入其他容器，會造成爭奪資源的情況。
        fargateTaskDefinition.addContainer("xray", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("public.ecr.aws/xray/aws-xray-daemon:latest"))
                .containerName("XRayInvoicesService")
                .logging(new AwsLogDriver(AwsLogDriverProps.builder()
                        .logGroup(new LogGroup(this, "XRayLogGroup", LogGroupProps.builder()
                                .logGroupName("XRayInvoicesService")
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .retention(RetentionDays.ONE_MONTH)
                                .build()))
                        .streamPrefix("XRayInvoicesService")
                        .build()))
                .portMappings(Collections.singletonList(PortMapping.builder()
                        .containerPort(2000)
                        .protocol(Protocol.UDP)
                        .build()))
                .cpu(128)
                .memoryLimitMiB(128)
                .build());

        //建立應用程式監聽器
        ApplicationListener applicationListener = invoicesServiceProps.applicationLoadBalancer()
                .addListener("InvoicesServiceAlbListener", ApplicationListenerProps.builder()
                        .port(9095)
                        .protocol(ApplicationProtocol.HTTP)
                        .loadBalancer(invoicesServiceProps.applicationLoadBalancer())
                        .build());

        //透過fargateTaskDefinition，建立Fargate。
        FargateService fargateService = new FargateService(this, "InvoicesService",
                FargateServiceProps.builder()
                        .serviceName("InvoicesService")
                        .cluster(invoicesServiceProps.cluster())
                        .taskDefinition(fargateTaskDefinition)
                        .desiredCount(2) //欲建立的實例數量
                        //DO NOT DO THIS IN PROD!!
                        .assignPublicIp(true) //會這樣設定是因為在VPC中，我們選擇不建立natGateways(擁有自己的IP)
                        .build());

        /*
        所有在AWS中的資源，即便都屬於你，但彼此溝通還是必須設定開通權限。
        所以要設定可以去ECR取得image
         */
        invoicesServiceProps.repository().grantPull(Objects.requireNonNull(fargateTaskDefinition.getExecutionRole()));

        //接受任何來自IP位址的任何內容，且在服務中設定TCP 9095 port來接收通訊傳入的請求。
        fargateService.getConnections().getSecurityGroups().get(0).addIngressRule(Peer.ipv4(invoicesServiceProps
                .vpc().getVpcCidrBlock()), Port.tcp(9095));

        //建立監聽器目標:，確認健康狀態。
        applicationListener.addTargets("InvoicesServiceAlbTarget",
                AddApplicationTargetsProps.builder()
                        .targetGroupName("invoicesServiceAlb")
                        .port(9095)
                        .protocol(ApplicationProtocol.HTTP)
                        .targets(Collections.singletonList(fargateService))
                        .deregistrationDelay(Duration.seconds(30))
                        .healthCheck(HealthCheck.builder()
                                .enabled(true)
                                .interval(Duration.seconds(30)) //每30秒發送一次健康檢查請求，確認實例健康狀態。
                                .timeout(Duration.seconds(10)) //超過10秒沒回應為逾時。
                                .path("/actuator/health") //SpringBoot中我們有引入此套件，負責確認應用程式健康狀態。
                                .port("9095")
                                .build())
                        .build()
        );

        //建立NetworkListener
        NetworkListener networkListener = invoicesServiceProps.networkLoadBalancer()
                .addListener("InvoicesServiceNlbListener", BaseNetworkListenerProps.builder()
                        .port(9095)
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
                        .build());

        networkListener.addTargets("InvoicesServiceNlbTarget",
                AddNetworkTargetsProps.builder()
                        .port(9095)
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
                        .targetGroupName("invoicesServiceNlb")
                        .targets(Collections.singletonList(
                                fargateService.loadBalancerTarget(LoadBalancerTargetOptions.builder()
                                        .containerName("invoicesService")
                                        .containerPort(9095)
                                        .protocol(Protocol.TCP)
                                        .build())))
                        .build());

        ScalableTaskCount scalableTaskCount = fargateService.autoScaleTaskCount(
                EnableScalingProps.builder()
                        .maxCapacity(4)
                        .minCapacity(2)
                        .build()
        );
    }
}

record InvoicesServiceProps(
        Vpc vpc,
        Cluster cluster,
        NetworkLoadBalancer networkLoadBalancer,
        ApplicationLoadBalancer applicationLoadBalancer,
        Repository repository
) {
}
