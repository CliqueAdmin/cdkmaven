package myorg;

import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.RemovalPolicy;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.IEventSource;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSource;
import software.amazon.awscdk.services.sqs.Queue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HelloCdkStack extends Stack {
    public HelloCdkStack(final Construct scope, final String id) throws InterruptedException {
        this(scope, id, null);
    }

    public HelloCdkStack(final Construct scope, final String id, final StackProps props) throws InterruptedException {
        super(scope, id, props);

        TableProps tableProps;
        Attribute partitionKey = Attribute.builder()
                .name("IGHID")
                .type(AttributeType.STRING)
                .build();
        Attribute titleKey = Attribute.builder()
                .name("Title")
                .type(AttributeType.STRING)
                .build();
        Attribute shelfLife = Attribute.builder()
                .name("ShelfLife")
                .type(AttributeType.NUMBER)
                .build();
        Attribute business = Attribute.builder()
                .name("Business")
                .type(AttributeType.NUMBER)
                .build();
        tableProps = TableProps.builder()
                .tableName("IGHItems")
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .partitionKey(partitionKey)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
        Table dynamodbTable = new Table(this, "IGHItems", tableProps);
        Map<String, String> environmentVariables = new HashMap<String, String>();
        environmentVariables.put("BUCKET", "bucket.getBucketName()");


        Queue queue = Queue.Builder.create(this, "itemqueue").queueName("item-replenish-queue").build();
        Queue itemDlq = Queue.Builder.create(this, "itemdlqueue").queueName("item-replenish-dlq").build();

        SqsEventSource sqsEventSource = SqsEventSource.Builder.create(queue).batchSize(1).build();
        List<IEventSource> eventSourceList = new ArrayList<>();
        eventSourceList.add(sqsEventSource);
//
//        Map<String, String> lambdaIntegrationMap = new HashMap<String, String>();
//        lambdaIntegrationMap.put("application/json", "{ \"statusCode\": \"200\" }");

        List<EndpointType> endpointTypeList = new ArrayList<>();
        endpointTypeList.add(EndpointType.REGIONAL);
        RestApi restApi = RestApi.Builder.create(this, "hello-rest-api").
                endpointTypes(endpointTypeList).restApiName("hello-rest-api").deploy(true)
                .build();


        final Function itemsreplenishment = Function.Builder.create(this, "ItemsReplenishmentController")
                .runtime(Runtime.JAVA_8)    // execution environment
                .code(Code.fromAsset("cdk.out/itemsreplenishment-1.0.zip"))  // code loaded from the "lambda" directory
                .handler("itemsreplenishment.events.ItemsReplenishmentSqsHandler")        // file is "hello", function is "handler"
                .environment(environmentVariables)
                .events(eventSourceList)
                .deadLetterQueueEnabled(true)
                .deadLetterQueue(itemDlq)
                .build();


        final Function itemsreplenishmentApi = Function.Builder.create(this, "ItemsReplenishmentApi")
                .runtime(Runtime.JAVA_8)    // execution environment
                .code(Code.fromAsset("cdk.out/itemsreplenishment-1.0.zip"))  // code loaded from the "lambda" directory
                .handler("itemsreplenishment.ItemsReplenishmentController")        // file is "hello", function is "handler"
                .environment(environmentVariables)
                .build();

        List<MethodResponse> methodResponses = new ArrayList<>();
        methodResponses.add(MethodResponse.builder().statusCode("200").build());

        IntegrationResponse integrationResponse = IntegrationResponse.builder().statusCode("200").build();
        List<IntegrationResponse> responses = new ArrayList<>();
        responses.add(integrationResponse);
        restApi.getRoot().addMethod("POST", LambdaIntegration.Builder.
                create(itemsreplenishmentApi).proxy(false).
                integrationResponses(responses).build(), MethodOptions.builder().methodResponses(methodResponses).build());
        ;


    }
}
