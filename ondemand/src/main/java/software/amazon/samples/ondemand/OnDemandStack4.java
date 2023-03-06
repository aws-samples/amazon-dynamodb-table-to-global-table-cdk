// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package software.amazon.samples.ondemand;

import software.amazon.awscdk.*;
import software.amazon.awscdk.customresources.*;
import software.amazon.awscdk.services.dynamodb.CfnGlobalTable;
import software.amazon.awscdk.services.dynamodb.ITable;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static software.amazon.samples.ondemand.StepConfig.*;

public class OnDemandStack4 extends Stack {

  public OnDemandStack4(final Construct parent, final String id) {
    this(parent, id, null);
  }

  public OnDemandStack4(final Construct parent, final String id, final StackProps props) {
    super(parent, id, StackProps.builder()
        .stackName(props.getStackName())
        .analyticsReporting(false)
        .build());

    // Refer to the table with its name
    ITable table = Table.fromTableName(this, "MyExternalTable", TABLE_NAME);

    // CfnGlobalTable
    CfnGlobalTable globalTable = createGlobalTable();

    // Lambda
    createFunction(table);

    // Output
    // Using a custom resource to get the ARN of table stream using AWS SDK - describeTable()
    outputTableStreamArn(getTableStreamArn());
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

  private void outputTableStreamArn(String tableStreamArn) {
    new CfnOutput(this, "TableStreamArn", CfnOutputProps.builder().value(tableStreamArn).build());
  }

  private String buildTableArn(String region) {
    return "arn:" + Aws.PARTITION + ":dynamodb:" + region + ":" + Aws.ACCOUNT_ID + ":table/" + TABLE_NAME;
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

  private CfnGlobalTable createGlobalTable() {
    List<CfnGlobalTable.AttributeDefinitionProperty> attributeDefinitions = List.of(CfnGlobalTable.AttributeDefinitionProperty.builder()
            .attributeName("PK")
            .attributeType("S")
            .build(),
        CfnGlobalTable.AttributeDefinitionProperty.builder()
            .attributeName("SK")
            .attributeType("S")
            .build());

    List<CfnGlobalTable.KeySchemaProperty> keySchema = List.of(CfnGlobalTable.KeySchemaProperty.builder()
            .attributeName("PK")
            .keyType("HASH")
            .build(),
        CfnGlobalTable.KeySchemaProperty.builder()
            .attributeName("SK")
            .keyType("RANGE")
            .build());

    CfnGlobalTable.StreamSpecificationProperty streamSpecification = CfnGlobalTable.StreamSpecificationProperty.builder()
        .streamViewType("NEW_AND_OLD_IMAGES")
        .build();

    List<CfnGlobalTable.GlobalSecondaryIndexProperty> indexes = List.of(CfnGlobalTable.GlobalSecondaryIndexProperty.builder()
        .indexName(GSI_NAME)
        .keySchema(List.of(CfnGlobalTable.KeySchemaProperty.builder()
            .attributeName("SK")
            .keyType("HASH")
            .build(), CfnGlobalTable.KeySchemaProperty.builder()
            .attributeName("PK")
            .keyType("RANGE")
            .build()))
        .projection(CfnGlobalTable.ProjectionProperty.builder()
            .projectionType("KEYS_ONLY")
            .build())
        .build()
    );

    List<CfnGlobalTable.ReplicaSpecificationProperty> replicas = DEFAULT_AND_ONE_REPLICA_REGION.stream().map(region -> {
      return CfnGlobalTable.ReplicaSpecificationProperty.builder()
          .region(region)
          .globalSecondaryIndexes(List.of(CfnGlobalTable.ReplicaGlobalSecondaryIndexSpecificationProperty.builder()
              .indexName(GSI_NAME)
              .build()))
          // Enable PiTR for the table
          .pointInTimeRecoverySpecification(CfnGlobalTable.PointInTimeRecoverySpecificationProperty.builder()
              .pointInTimeRecoveryEnabled(Boolean.TRUE)
              .build())
          .build();
    }).collect(Collectors.toList());

    // Global Table
    CfnGlobalTable table = CfnGlobalTable.Builder.create(this, "MyGlobalTable")
        .tableName(TABLE_NAME)
        .billingMode("PAY_PER_REQUEST")
        .attributeDefinitions(attributeDefinitions)
        .keySchema(keySchema)
        .streamSpecification(streamSpecification)
        .globalSecondaryIndexes(indexes)
        .replicas(replicas)
        .build();

    // Protect table and replica table(s) from deletion
    table.applyRemovalPolicy(RemovalPolicy.RETAIN);
    return table;
  }
}