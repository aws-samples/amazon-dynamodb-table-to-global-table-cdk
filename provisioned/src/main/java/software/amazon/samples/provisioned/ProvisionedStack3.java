// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package software.amazon.samples.provisioned;

import software.amazon.awscdk.*;
import software.amazon.awscdk.customresources.*;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

import static software.amazon.samples.provisioned.StepConfig.*;

public class ProvisionedStack3 extends Stack {

  public ProvisionedStack3(final Construct parent, final String id) {
    this(parent, id, null);
  }

  public ProvisionedStack3(final Construct parent, final String id, final StackProps props) {
    super(parent, id, StackProps.builder()
        .stackName(props.getStackName())
        .analyticsReporting(false)
        .build());

    // Table
    // To prepare removing the Table construct from the stack in the next step
    // Use a new variable to refer to the table created in the previous steps
    Table myTable = createTable();
    createGsi(myTable);

    // Protect table and replica table(s) from deletion
    protectTableFromDeletion(myTable);

    // Enable PiTR for the replica table
    // You need to enable PiTR for all the replica tables.
    for (String replicaRegion : REPLICA_REGIONS) {
      pointInTimeRecoveryForReplicaTable(replicaRegion);
    }

    // To prepare removing the Table construct from the stack in the next step
    // Refer to the table with its name and a new construct id
    ITable table = Table.fromTableName(this, "MyExternalTable", TABLE_NAME);

    // Lambda
    createFunction(table);

    // Output
    // Using a custom resource to get the ARN of table stream using AWS SDK - describeTable()
    // outputTableStreamArn(table);
    outputTableStreamArn(getTableStreamArn());
  }

  private Table createTable() {
    return Table.Builder.create(this, "MyTable")
        .tableName(TABLE_NAME)
        .partitionKey(Attribute.builder().name("PK").type(AttributeType.STRING).build())
        .sortKey(Attribute.builder().name("SK").type(AttributeType.STRING).build())
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .replicationRegions(DEFAULT_AND_ONE_REPLICA_REGION)
        // Enable PiTR for the table - It creates a snapshot of the table before deletion.
        .pointInTimeRecovery(Boolean.TRUE)
        .build();
  }

  private void createGsi(Table table) {
    GlobalSecondaryIndexProps gsi = GlobalSecondaryIndexProps.builder()
        .indexName(GSI_NAME)
        .partitionKey(Attribute.builder().name("SK").type(AttributeType.STRING).build())
        .sortKey(Attribute.builder().name("PK").type(AttributeType.STRING).build())
        .projectionType(ProjectionType.KEYS_ONLY)
        .build();
    table.addGlobalSecondaryIndex(gsi);
  }

  // Using ITable instead of Table
  private void createFunction(ITable table) {
    Function lambda = new Function(this, "MyFunction", FunctionProps.builder()
        .code(Code.fromAsset(FUNCTION_PATH))
        .handler(FUNCTION_HANDLER)
        .runtime(Runtime.JAVA_11)
        .environment(Map.of(FUNCTION_ENV_VARIABLE, table.getTableName()))
        .timeout(Duration.seconds(30))
        .memorySize(1024)
        .functionName(FUNCTION_NAME)
        .build());
    table.grant(lambda, "dynamodb:PutItem", "dynamodb:Scan");
  }

  /*
  private void outputTableStreamArn(Table table) {
    new CfnOutput(this, "TableStreamArn", CfnOutputProps.builder().value(table.getTableStreamArn()).build());
  }
  */

  private void protectTableFromDeletion(Table table) {
    // Adding retention policy to avoid table deletion.
    // Set it to RemovalPolicy.RETAIN
    // This only protects the table in eu-west-1 region because the stack was deployed in this region.
    table.applyRemovalPolicy(RemovalPolicy.RETAIN);

    // Add retention policy to all custom resources that have Custom::DynamoDBReplica type
    // because the replica tables were created using AWS SDK.
    // This is to ensure that the replica tables are protected from deletion.
    protectTableReplicaFromDeletion(table);
  }

  private static void protectTableReplicaFromDeletion(Table table) {
    table.getNode().getChildren().stream()
        .filter(child -> child instanceof CustomResource)
        .forEach(customResource -> customResource.getNode().getChildren()
            .stream()
            .filter(resource -> resource instanceof CfnResource)
            .filter(cfnResource -> ((CfnResource) cfnResource).getCfnResourceType().equals("Custom::DynamoDBReplica"))
            .forEach(replica -> ((CfnResource) replica).applyRemovalPolicy(RemovalPolicy.RETAIN))
        );
  }

  private String buildTableArn(String region) {
    return "arn:" + Aws.PARTITION + ":dynamodb:" + region + ":" + Aws.ACCOUNT_ID + ":table/" + TABLE_NAME;
  }

  private void pointInTimeRecoveryForReplicaTable(String replicaRegion) {
    AwsSdkCall updateContinuousBackups = AwsSdkCall.builder()
        .service("DynamoDB")
        .action("updateContinuousBackups")
        .region(replicaRegion)
        .parameters(Map.of(
            "TableName", TABLE_NAME,
            "PointInTimeRecoverySpecification", Map.of("PointInTimeRecoveryEnabled", true)))
        .outputPaths(List.of("ContinuousBackupsDescription"))
        .physicalResourceId(PhysicalResourceId.of(TABLE_NAME)).build();

    AwsCustomResource.Builder.create(this, "MyTableUpdateContinuousBackups-" + replicaRegion)
        .onCreate(updateContinuousBackups)
        .installLatestAwsSdk(false)
        .policy(AwsCustomResourcePolicy.fromSdkCalls(SdkCallsPolicyOptions.builder()
            .resources(List.of(buildTableArn(replicaRegion)))
            .build()))
        .build();
  }

  private void outputTableStreamArn(String tableStreamArn) {
    new CfnOutput(this, "TableStreamArn", CfnOutputProps.builder().value(tableStreamArn).build());
  }

  private String getTableStreamArn() {
    AwsSdkCall describeTable = AwsSdkCall.builder()
        .service("DynamoDB")
        .action("describeTable")
        .region(AWS_DEFAULT_REGION)
        .parameters(Map.of("TableName", TABLE_NAME))
        .outputPaths(List.of("Table.LatestStreamArn"))
        .physicalResourceId(PhysicalResourceId.of(TABLE_NAME)).build();
    AwsCustomResource describeTableCustomResource = AwsCustomResource.Builder.create(this, "MyTableDescribeTable")
        .onCreate(describeTable)
        .onUpdate(describeTable)
        .installLatestAwsSdk(false)
        .policy(AwsCustomResourcePolicy.fromSdkCalls(SdkCallsPolicyOptions.builder()
            .resources(List.of(buildTableArn(AWS_DEFAULT_REGION)))
            .build()))
        .build();

    return describeTableCustomResource.getResponseField("Table.LatestStreamArn");
  }
}