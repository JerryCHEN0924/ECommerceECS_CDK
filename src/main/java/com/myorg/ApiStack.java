/**
 * ApiGateway Stack
 */
package com.myorg;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.NetworkLoadBalancer;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

import java.util.Arrays;
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

        Resource productsResource = this.createProductsResource(restApi, apiStackPros);

        this.createProductEventsResource(restApi, apiStackPros, productsResource);
    }

    private void createProductEventsResource(RestApi restApi, ApiStackProps apiStackProps,
                                             Resource productResource) {
        // product/events
        Resource productEventsResource = productResource.addResource("events");

        Map<String, String> productsEventsIntegrationParameters = new HashMap<>();
        productsEventsIntegrationParameters.put("integration.request.header.requestId", "context.requestId");

        Map<String, Boolean> productsEventsMethodParameters = new HashMap<>();
        productsEventsMethodParameters.put("method.request.header.requestId", false);
        productsEventsMethodParameters.put("method.request.querystring.eventType", true);
        productsEventsMethodParameters.put("method.request.querystring.limit", false);
        productsEventsMethodParameters.put("method.request.querystring.from", false);
        productsEventsMethodParameters.put("method.request.querystring.to", false);
        productsEventsMethodParameters.put("method.request.querystring.exclusiveStartTimestamp", false);

        // GET /products/events?eventType=PRODUCT_CREATE&limit=10&from=1&to=5&exclusiveStartTimestamp=123
        productEventsResource.addMethod("GET", new Integration(
                        IntegrationProps.builder()
                                .type(IntegrationType.HTTP_PROXY)
                                .integrationHttpMethod("GET")
                                .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                        ":9090/api/products/events")
                                .options(IntegrationOptions.builder()
                                        .vpcLink(apiStackProps.vpcLink())
                                        .connectionType(ConnectionType.VPC_LINK)
                                        .requestParameters(productsEventsIntegrationParameters)
                                        .build())
                                .build()),
                MethodOptions.builder()
                        .requestValidator(new RequestValidator(this, "ProductEventsValidator",
                                RequestValidatorProps.builder()
                                        .restApi(restApi)
                                        .requestValidatorName("ProductEventsValidator")
                                        .validateRequestParameters(true)
                                        .build()))
                        .requestParameters(productsEventsMethodParameters)
                        .build()
        );
    }

    private Resource createProductsResource(RestApi restApi, ApiStackProps apiStackProps) {
        Map<String, String> productsIntegrationParameters = new HashMap<>();
        productsIntegrationParameters.put("integration.request.header.requestId", "context.requestId");

        Map<String, Boolean> productsMethodParameters = new HashMap<>();
        productsMethodParameters.put("method.request.header.requestId", false);
        productsMethodParameters.put("method.request.querystring.code", false);

        // /products
        Resource productResource = restApi.getRoot().addResource("products");

        // GET /products
        // GET /products?code=CODE1
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

        RequestValidator productRequestValidator = new RequestValidator(this, "ProductRequestValidator",
                RequestValidatorProps.builder()
                        .restApi(restApi)
                        .requestValidatorName("Product request validator")
                        .validateRequestBody(true)
                        .build());

        Map<String, JsonSchema> productModelProperties = new HashMap<>();
        productModelProperties.put("name", JsonSchema.builder()
                .type(JsonSchemaType.STRING)
                .minimum(5)
                .maximum(50)
                .build());

        productModelProperties.put("code", JsonSchema.builder()
                .type(JsonSchemaType.STRING)
                .minimum(5)
                .maximum(15)
                .build());

        productModelProperties.put("model", JsonSchema.builder()
                .type(JsonSchemaType.STRING)
                .minimum(5)
                .maximum(15)
                .build());

        productModelProperties.put("price", JsonSchema.builder()
                .type(JsonSchemaType.NUMBER)
                .minimum(10.0)
                .maximum(1000.0)
                .build());

        Model productModel = new Model(this, "ProductModel", ModelProps.builder()
                .modelName("ProductModel")
                .restApi(restApi)
                .contentType("application/json")
                .schema(JsonSchema.builder()
                        .type(JsonSchemaType.OBJECT)
                        .properties(productModelProperties)
                        .required(Arrays.asList("name", "code"))
                        .build())
                .build());

        Map<String, Model> productRequestModel = new HashMap<>();
        productRequestModel.put("application/json", productModel);

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
                        .requestValidator(productRequestValidator)
                        .requestModels(productRequestModel)
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
                        .requestValidator(productRequestValidator)
                        .requestModels(productRequestModel)
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

        return productResource;
    }
}

record ApiStackProps(
        NetworkLoadBalancer networkLoadBalancer,
        VpcLink vpcLink
) {
}
