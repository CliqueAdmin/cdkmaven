package myorg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.Duration;
import software.amazon.awscdk.core.RemovalPolicy;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.apigateway.EndpointType;
import software.amazon.awscdk.services.apigateway.IntegrationResponse;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.MethodResponse;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.dynamodb.TableProps;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.IEventSource;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSource;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.IBucket;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.stepfunctions.Chain;
import software.amazon.awscdk.services.stepfunctions.StateMachine;
import software.amazon.awscdk.services.stepfunctions.Task;
import software.amazon.awscdk.services.stepfunctions.TaskInput;
import software.amazon.awscdk.services.stepfunctions.tasks.SendToQueue;

public class HelloCdkStack extends Stack {

  public HelloCdkStack(
      final Construct scope,
      final String id) throws InterruptedException {
    this(scope, id, null);
  }

  public HelloCdkStack(
      final Construct scope,
      final String id,
      final StackProps props) throws InterruptedException {
    super(scope, id, props);

    IBucket itemLanesBucket = new Bucket(this, "item-lanes-snapshot");

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

    TableProps tablePropsSnap;
    Attribute dataFrameId = Attribute.builder()
        .name("dataFrameId")
        .type(AttributeType.STRING)
        .build();
    Attribute dataSourceName = Attribute.builder()
        .name("dataSourceName")
        .type(AttributeType.STRING)
        .build();
    Attribute captureDateTime = Attribute.builder()
        .name("captureDateTime")
        .type(AttributeType.STRING)
        .build();

    tablePropsSnap = TableProps.builder()
        .tableName("DataFrames")

        .billingMode(BillingMode.PAY_PER_REQUEST)
        .partitionKey(dataSourceName)
        .sortKey(captureDateTime)
        .removalPolicy(RemovalPolicy.DESTROY)
        .build();
    Table dataFrames = new Table(this, "DataFrames", tablePropsSnap);

    TableProps tablePropsLanes;
    Attribute laneItem = Attribute.builder()
        .name("item")
        .type(AttributeType.STRING)
        .build();
    Attribute itemLaneId = Attribute.builder()
        .name("itemLaneId")
        .type(AttributeType.STRING)
        .build();

    tablePropsLanes = TableProps.builder()
        .tableName("ItemLanes")
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .partitionKey(laneItem)
        .sortKey(itemLaneId)
        .removalPolicy(RemovalPolicy.DESTROY)
        .build();
    Table itemLanes = new Table(this, "ItemLanes", tablePropsLanes);

    Queue queue = Queue.Builder.create(this, "itemqueue")
        .queueName("item-replenish-queue")
        .
            visibilityTimeout(Duration.minutes(10))
        .build();
    Queue itemDlq = Queue.Builder.create(this, "itemdlqueue")
        .queueName("item-replenish-dlq")
        .build();

    SqsEventSource sqsEventSource = SqsEventSource.Builder.create(queue)
        .batchSize(1)
        .build();
    List<IEventSource> eventSourceList = new ArrayList<>();
    eventSourceList.add(sqsEventSource);

    List<EndpointType> endpointTypeList = new ArrayList<>();
    endpointTypeList.add(EndpointType.REGIONAL);
    RestApi restApi = RestApi.Builder.create(this, "hello-rest-api")
        .endpointTypes(endpointTypeList)
        .restApiName("hello-rest-api")
        .deploy(true)
        .build();

    TaskInput taskInput = TaskInput.fromDataAt("$.hello");

    Task submitJob = Task.Builder.create(this, "Submit Job")
        .task(SendToQueue.Builder.create(queue)
            .messageBody(taskInput)
            .build())
        //                        .inputPath("$.hello")
        //                        .resultPath("$.status")
        .build();
    Chain chain = Chain.start(submitJob);
    StateMachine stateMachine = StateMachine.Builder.create(this, "StateMachine")
        .definition(chain)
        .stateMachineName("Hello-State")
        .timeout(Duration.seconds(30))
        .build();
    List<String> resources = new ArrayList<>();
    resources.add("*");
    List<String> actions = new ArrayList<>();
    actions.add("states:StartExecution");
    actions.add("dynamodb:Query");
    actions.add("dynamodb:UpdateItem");
    actions.add("s3:PutObject");
    actions.add("s3:GetObject");
    List<PolicyStatement> policyStatements = new ArrayList<>();
    policyStatements.add(PolicyStatement.Builder.create()
        .resources(resources)
        .actions(actions)
        .build());
    Map<String, String> environmentVariables = new HashMap<String, String>();
    environmentVariables.put("STATE_MACHINE_ARN", stateMachine.getStateMachineArn());
    environmentVariables.put("DATA_FRAME_TABLE_NAME", dataFrames.getTableName());
    environmentVariables.put("ITEM_LANES_TABLE_NAME", itemLanes.getTableName());
    environmentVariables.put("ITEM_LANES_BUCKET_NAME", itemLanesBucket.getBucketName());
    final Function itemLanesApi = Function.Builder.create(this, "ItemLanesApi")
        .runtime(Runtime.JAVA_8)// execution environment
        .code(Code.fromAsset(
            "cdk.out/itemsreplenishment-1.0.zip"))  // code loaded from the "lambda" directory
        .handler(
            "ItemLanes.apis.ItemLanesController")        // file is "hello", function is "handler"
        .timeout(Duration.seconds(30))
        .initialPolicy(policyStatements)
        .environment(environmentVariables)
        .memorySize(256)
        .build();

    List<MethodResponse> methodResponses = new ArrayList<>();
    methodResponses.add(MethodResponse.builder()
        .statusCode("200")
        .build());

    IntegrationResponse integrationResponse = IntegrationResponse.builder()
        .statusCode("200")
        .build();
    List<IntegrationResponse> responses = new ArrayList<>();
    responses.add(integrationResponse);

    final Function itemsreplenishmentAddApi = Function.Builder.create(this, "ItemsReplenishmentAddApi")
        .runtime(Runtime.JAVA_8)    // execution environment
        .code(Code.fromAsset("cdk.out/itemsreplenishment-1.0.zip"))
        .handler("ItemLanes.apis.AddMockItemLanesController")
        .timeout(Duration.seconds(30))
        .initialPolicy(policyStatements)
        .environment(environmentVariables)
        .memorySize(256)
        .build();

    restApi.getRoot()
        .addResource("addreplenishments")
        .addMethod("POST", LambdaIntegration.Builder.
            create(itemsreplenishmentAddApi)
            .proxy(false)
            .integrationResponses(responses)
            .build(), MethodOptions.builder()
            .methodResponses(methodResponses)
            .build());

    final Function itemsreplenishmentApi = Function.Builder.create(this, "ItemsReplenishmentApi")
        .runtime(Runtime.JAVA_8)    // execution environment
        .code(Code.fromAsset("cdk.out/itemsreplenishment-1.0.zip"))
        .handler("itemsreplenishment.ItemsReplenishmentController")
        .build();

    restApi.getRoot()
        .addResource("replenishments")
        .addMethod("POST", LambdaIntegration.Builder.
            create(itemsreplenishmentApi)
            .proxy(false)
            .integrationResponses(responses)
            .build(), MethodOptions.builder()
            .methodResponses(methodResponses)
            .build());

    restApi.getRoot()
        .addResource("itemlanes")
        .addMethod("POST", LambdaIntegration.Builder.
            create(itemLanesApi)
            .proxy(false)
            .integrationResponses(responses)
            .build(), MethodOptions.builder()
            .methodResponses(methodResponses)
            .build());

    final Function itemsreplenishment = Function.Builder
        .create(this, "ItemsReplenishmentController")
        .runtime(Runtime.JAVA_8)    // execution environment
        .code(Code.fromAsset(
            "cdk.out/itemsreplenishment-1.0.zip"))  // code loaded from the "lambda" directory
        .handler(
            "itemsreplenishment.events.ItemsReplenishmentSqsHandler")        // file is "hello", function is "handler"
        .environment(environmentVariables)
        .initialPolicy(policyStatements)
        .events(eventSourceList)
        .memorySize(256)
        .timeout(Duration.minutes(10))
        .deadLetterQueueEnabled(true)
        .deadLetterQueue(itemDlq)
        .build();
  }
}
