/**
 * Cluster Stack 叢集Stack
 */
package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ClusterProps;
import software.constructs.Construct;

public class ClusterStack extends Stack {
    private final Cluster cluster;

    public ClusterStack(final Construct scope, final String id, final StackProps props, ClusterStackProps clusterStackProps) {
        super(scope, id, props);


        this.cluster = new Cluster(this,"Cluster", ClusterProps.builder()
                .clusterName("ECommerce")
                .vpc(clusterStackProps.vpc()) //表示使用同一個vpc
                .containerInsights(true) //獲得容器的資訊如:網路、硬碟、記憶體消耗...等
                .build());
    }

    public Cluster getCluster() {
        return cluster;
    }
}

record ClusterStackProps(Vpc vpc) {
}
