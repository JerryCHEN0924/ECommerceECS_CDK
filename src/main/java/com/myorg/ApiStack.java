/**
 * ApiGateway Stack
 */
package com.myorg;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.NetworkLoadBalancer;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

import java.util.HashMap;
import java.util.Map;

public class ApiStack extends Stack {
    public ApiStack(final Construct scope, final String id, final StackProps props, ApiStackProps apiStackPros) {
        super(scope, id, props);

        LogGroup logGroup = new LogGroup(this, "ECommerceApiLogs", LogGroupProps.builder()
                .logGroupName("ECommerceAPI")
                .removalPolicy(RemovalPolicy.DESTROY)
                .retention(RetentionDays.ONE_MONTH)
                .build());

        RestApi restApi = new RestApi(this, "RestApi", RestApiProps.builder()
                .restApiName("ECommerceAPI")
                .cloudWatchRole(true)
                .deployOptions(StageOptions.builder()
                        .loggingLevel(MethodLoggingLevel.INFO)
                        .accessLogDestination(new LogGroupLogDestination(logGroup))
                        .accessLogFormat(
                                AccessLogFormat.jsonWithStandardFields(
                                        JsonWithStandardFieldProps.builder()
                                                .caller(true)
                                                .httpMethod(true)
                                                .ip(true)
                                                .protocol(true)
                                                .requestTime(true)
                                                .resourcePath(true)
                                                .responseLength(true)
                                                .status(true)
                                                .user(true)
                                                .build()
                                )
                        )
                        .build())
                .build());

        this.createProductsResource(restApi, apiStackPros);
    }

    private void createProductsResource(RestApi restApi, ApiStackProps apiStackProps) {
        Map<String, String> productsIntegrationParameters = new HashMap<>();
        productsIntegrationParameters.put("integration.request.header.requestId", "context.requestId");

        Map<String, Boolean> productsMethodParameters = new HashMap<>();
        productsMethodParameters.put("method.request.header", false);

        // /products
        Resource productResource = restApi.getRoot().addResource("products");

        // GET /products
        productResource.addMethod("GET", new Integration(
                        IntegrationProps.builder()
                                .type(IntegrationType.HTTP_PROXY)
                                .integrationHttpMethod("GET")
                                .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                        ":8080/api/products")
                                .options(IntegrationOptions.builder()
                                        .vpcLink(apiStackProps.vpcLink())
                                        .connectionType(ConnectionType.VPC_LINK)
                                        .requestParameters(productsIntegrationParameters)
                                        .build())
                                .build()),
                MethodOptions.builder()
                        .requestParameters(productsMethodParameters)
                        .build()
        );

        // POST /products
        productResource.addMethod("POST", new Integration(
                        IntegrationProps.builder()
                                .type(IntegrationType.HTTP_PROXY)
                                .integrationHttpMethod("POST")
                                .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                        ":8080/api/products")
                                .options(IntegrationOptions.builder()
                                        .vpcLink(apiStackProps.vpcLink())
                                        .connectionType(ConnectionType.VPC_LINK)
                                        .requestParameters(productsIntegrationParameters)
                                        .build())
                                .build()),
                MethodOptions.builder()
                        .requestParameters(productsMethodParameters)
                        .build());

        // PUT /products/{id}
        Map<String, String> productIdIntegrationParameters = new HashMap<>();
        productIdIntegrationParameters.put("integration.request.path.id", "method.request.path.id");
        productIdIntegrationParameters.put("integration.request.header.requestId", "context.requestId");

        Map<String, Boolean> productIdMethodParameters = new HashMap<>();
        productIdMethodParameters.put("method.request.path.id", true);
        productIdMethodParameters.put("method.request.header", false);

        Resource productIdResource = productResource.addResource("{id}");
        productIdResource.addMethod("PUT", new Integration(
                        IntegrationProps.builder()
                                .type(IntegrationType.HTTP_PROXY)
                                .integrationHttpMethod("PUT")
                                .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                        ":8080/api/products/{id}")
                                .options(IntegrationOptions.builder()
                                        .vpcLink(apiStackProps.vpcLink())
                                        .connectionType(ConnectionType.VPC_LINK)
                                        .requestParameters(productIdIntegrationParameters)
                                        .build())
                                .build()),
                MethodOptions.builder()
                        .requestParameters(productsMethodParameters) //加入檢查是否帶參數，API GATEWAY在此做第一次驗證。
                        .build());

        // GET /products/{id}
        productIdResource.addMethod("GET", new Integration(
                IntegrationProps.builder()
                        .type(IntegrationType.HTTP_PROXY)
                        .integrationHttpMethod("GET")
                        .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                ":8080/api/products/{id}")
                        .options(IntegrationOptions.builder()
                                .vpcLink(apiStackProps.vpcLink())
                                .connectionType(ConnectionType.VPC_LINK)
                                .requestParameters(productIdIntegrationParameters)
                                .build())
                        .build()), MethodOptions.builder()
                .requestParameters(productIdMethodParameters) //加入檢查是否帶參數，API GATEWAY在此做第一次驗證。
                .build());

        // DELETE /products/{id}
        productIdResource.addMethod("DELETE", new Integration(
                IntegrationProps.builder()
                        .type(IntegrationType.HTTP_PROXY)
                        .integrationHttpMethod("DELETE")
                        .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                ":8080/api/products/{id}")
                        .options(IntegrationOptions.builder()
                                .vpcLink(apiStackProps.vpcLink())
                                .connectionType(ConnectionType.VPC_LINK)
                                .requestParameters(productIdIntegrationParameters)
                                .build())
                        .build()), MethodOptions.builder()
                .requestParameters(productIdMethodParameters) //加入檢查是否帶參數，API GATEWAY在此做第一次驗證。
                .build());
    }
}

record ApiStackProps(
        NetworkLoadBalancer networkLoadBalancer,
        VpcLink vpcLink
) {
}
