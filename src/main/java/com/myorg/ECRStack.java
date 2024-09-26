package com.myorg;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecr.RepositoryProps;
import software.amazon.awscdk.services.ecr.TagMutability;
import software.constructs.Construct;

public class ECRStack extends Stack {

    private final Repository productsServiceRepository;

    public ECRStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        this.productsServiceRepository = new Repository(this, "ProductsService", RepositoryProps.builder()
                .repositoryName("productsservice")//這個Name必須跟image的名稱一樣
                .removalPolicy(RemovalPolicy.DESTROY) //刪除Stack時，REPO也會跟著被刪除。如未設定RemovalPolicy.DESTROY，預設是RETAIN
                .imageTagMutability(TagMutability.IMMUTABLE)//若tag版本號未修改，但這個image有異動，則上傳時可覆蓋原Image。
                .emptyOnDelete(true)//如果ECR被刪除，則自動刪除image。
                .build());
    }

    public Repository getProductsServiceRepository() {
        return productsServiceRepository;
    }
}
