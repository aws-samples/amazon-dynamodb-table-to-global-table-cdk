// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package software.amazon.samples.provisioned;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
import software.constructs.Construct;

import java.util.Map;

import static software.amazon.samples.provisioned.StepConfig.*;

public class ProvisionedStack0 extends Stack {

  public ProvisionedStack0(final Construct parent, final String id) {
    this(parent, id, null);
  }

  public ProvisionedStack0(final Construct parent, final String id, final StackProps props) {
    super(parent, id, StackProps.builder()
        .stackName(props.getStackName())
        .analyticsReporting(false)
        .build());

    // Table
    Table table = createTable();
    enableAutoScalingForTable(table);
    createGsi(table);
    enableAutoScalingForGsi(table);

    // Lambda
    createFunction(table);

    // Output
    outputTableStreamArn(table);
  }

  private Table createTable() {
    return Table.Builder.create(this, "MyTable")
        .tableName(TABLE_NAME)
        .partitionKey(Attribute.builder().name("PK").type(AttributeType.STRING).build())
        .sortKey(Attribute.builder().name("SK").type(AttributeType.STRING).build())
        .billingMode(BillingMode.PROVISIONED)
        .replicationRegions(DEFAULT_AND_ONE_REPLICA_REGION)
        .build();
  }

  private void enableAutoScalingForTable(Table table) {
    table.autoScaleWriteCapacity(getEnableScalingProps(WRITE_AUTO_SCALING_MIN, WRITE_AUTO_SCALING_MAX))
        .scaleOnUtilization(getUtilizationScalingProps(WRITE_AUTO_SCALING_UTILIZATION_TARGET));

    table.autoScaleReadCapacity(getEnableScalingProps(READ_AUTO_SCALING_MIN, READ_AUTO_SCALING_MAX))
        .scaleOnUtilization(getUtilizationScalingProps(READ_AUTO_SCALING_UTILIZATION_TARGET));
  }

  private void enableAutoScalingForGsi(Table table) {
    table.autoScaleGlobalSecondaryIndexWriteCapacity(GSI_NAME, getEnableScalingProps(WRITE_AUTO_SCALING_MIN, WRITE_AUTO_SCALING_MAX))
        .scaleOnUtilization(getUtilizationScalingProps(WRITE_AUTO_SCALING_UTILIZATION_TARGET));

    table.autoScaleGlobalSecondaryIndexReadCapacity(GSI_NAME, getEnableScalingProps(READ_AUTO_SCALING_MIN, READ_AUTO_SCALING_MAX))
        .scaleOnUtilization(getUtilizationScalingProps(READ_AUTO_SCALING_UTILIZATION_TARGET));
  }

  private static UtilizationScalingProps getUtilizationScalingProps(int targetUtilizationPercent) {
    return UtilizationScalingProps.builder()
        .targetUtilizationPercent(targetUtilizationPercent)
        .build();
  }

  private static EnableScalingProps getEnableScalingProps(int min, int max) {
    return EnableScalingProps.builder()
        .minCapacity(min)
        .maxCapacity(max)
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

  private void createFunction(Table table) {
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

  private void outputTableStreamArn(Table table) {
    new CfnOutput(this, "TableStreamArn", CfnOutputProps.builder().value(table.getTableStreamArn()).build());
  }
}