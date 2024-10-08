package com.myorg;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class ECommerceEcsCdkApp {
    public static void main(final String[] args) {
        App app = new App();
        Environment environment = Environment.builder()
                .account("339713017818")
                .region("us-east-1")
                .build();

        Map<String, String> infraTags = new HashMap<>();
        infraTags.put("team", "Alpha");
        infraTags.put("cost", "ECommerceInfra");

        ECRStack ecrStack = new ECRStack(app, "Ecr", StackProps.builder()
                .env(environment)
                .tags(infraTags)
                .build());

        VpcStack vpcStack = new VpcStack(app, "Vpc", StackProps.builder()
                .env(environment)
                .tags(infraTags)
                .build());

        ClusterStack clusterStack = new ClusterStack(app, "Cluster", StackProps.builder()
                .env(environment)
                .tags(infraTags)
                .build(), new ClusterStackProps(vpcStack.getVpc()));
        clusterStack.addDependency(vpcStack);

        NlbStack nlbStack = new NlbStack(app, "Nlb", StackProps.builder()
                .env(environment)
                .tags(infraTags)
                .build(), new NlbStackProps(vpcStack.getVpc()));
        nlbStack.addDependency(vpcStack);

        Map<String, String> productsServiceTags = new HashMap<>();
        productsServiceTags.put("team", "Alpha");
        productsServiceTags.put("cost", "ProductsServiceInfra");

        ProductsServiceStack productsServiceStack = new ProductsServiceStack(app, "ProductsService",
                StackProps.builder()
                        .env(environment)
                        .tags(productsServiceTags)
                        .build(),
                new ProductsServiceProps(
                        vpcStack.getVpc(),
                        clusterStack.getCluster(),
                        nlbStack.getNetworkLoadBalancer(),
                        nlbStack.getApplicationLoadBalancer(),
                        ecrStack.getProductsServiceRepository()));
        productsServiceStack.addDependency(vpcStack);
        productsServiceStack.addDependency(clusterStack);
        productsServiceStack.addDependency(nlbStack);
        productsServiceStack.addDependency(ecrStack);

        ApiStack apiStack = new ApiStack(app, "Api", StackProps.builder()
                .env(environment)
                .tags(infraTags)
                .build(),
                new ApiStackProps(nlbStack.getNetworkLoadBalancer(),
                        nlbStack.getVpcLink()));
        apiStack.addDependency(nlbStack);
        app.synth();
    }
}

