// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package software.amazon.samples.ondemand;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.dynamodb.CfnGlobalTable;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static software.amazon.samples.ondemand.StepConfig.*;

public class OnDemandStack6 extends Stack {

  public OnDemandStack6(final Construct parent, final String id) {
    this(parent, id, null);
  }

  public OnDemandStack6(final Construct parent, final String id, final StackProps props) {
    super(parent, id, StackProps.builder()
        .stackName(props.getStackName())
        .analyticsReporting(false)
        .build());

    // CfnGlobalTable
    CfnGlobalTable globalTable = createGlobalTable();

    // Lambda
    createFunction(globalTable);

    // Output
    outputTableStreamArn(globalTable);
  }

  private void outputTableStreamArn(CfnGlobalTable table) {
    new CfnOutput(this, "TableStreamArn", CfnOutputProps.builder().value(table.getAttrStreamArn()).build());
  }

  private void createFunction(CfnGlobalTable table) {
    Function lambda = new Function(this, "MyFunction", FunctionProps.builder()
        .code(Code.fromAsset(FUNCTION_PATH))
        .handler(FUNCTION_HANDLER)
        .runtime(Runtime.JAVA_11)
        .environment(Map.of(FUNCTION_ENV_VARIABLE, table.getTableName()))
        .timeout(Duration.seconds(30))
        .memorySize(1024)
        .functionName(FUNCTION_NAME)
        .build());

    lambda.getRole()
        .addToPrincipalPolicy(PolicyStatement.Builder.create()
            .actions(List.of("dynamodb:PutItem", "dynamodb:Scan"))
            .resources(List.of(table.getAttrArn()))
            .effect(Effect.ALLOW)
            .build());
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

    // Adding a new replica region to the existing Global Table
    List<CfnGlobalTable.ReplicaSpecificationProperty> replicas = DEFAULT_AND_TWO_REPLICA_REGIONS.stream().map(region -> {
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