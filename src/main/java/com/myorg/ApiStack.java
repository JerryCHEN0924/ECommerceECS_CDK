/**
 * ApiGateway Stack
 */
package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.NetworkLoadBalancer;
import software.constructs.Construct;

public class ApiStack extends Stack {
    public ApiStack(final Construct scope, final String id, final StackProps props, ApiStackProps apiStackPros) {
        super(scope, id, props);
        RestApi restApi = new RestApi(this, "RestApi", RestApiProps.builder()
                .restApiName("ECommerceAPI")
                .build());

        this.createProductsResource(restApi, apiStackPros);
    }

    private void createProductsResource(RestApi restApi, ApiStackProps apiStackProps) {
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
                                .build())
                        .build()
        ));
    }
}

record ApiStackProps(
        NetworkLoadBalancer networkLoadBalancer,
        VpcLink vpcLink
) {
}
