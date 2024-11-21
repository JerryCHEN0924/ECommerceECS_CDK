/**
 * Virtual Private Cloud Stack 隔離的虛擬網路環境Stack
 */
package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.VpcProps;
import software.constructs.Construct;

public class VpcStack extends Stack {
    private final Vpc vpc;

    public VpcStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        this.vpc = new Vpc(this,"Vpc", VpcProps.builder()
                .vpcName("ECommerceVPC")
                .maxAzs(2)
                //DO NOT DO THIS IN PROD!! 只是因為練習而節省建立基礎設施的費用，所以特地設定此參數為0。
                .natGateways(0)
                .build());
    }

    public Vpc getVpc() {
        return vpc;
    }
}
