// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package software.amazon.samples.provisioned;

import java.util.List;

public class StepConfig {
  public static final String AWS_DEFAULT_REGION = "eu-west-1";
  public static final String REPLICATION_REGION_1 = "eu-north-1";
  public static final List<String> REPLICA_REGIONS = List.of(REPLICATION_REGION_1);
  public static final List<String> DEFAULT_AND_ONE_REPLICA_REGION = List.of(AWS_DEFAULT_REGION, REPLICATION_REGION_1);
  public static final String STACK_NAME = "ProvisionedStack";
  public static final String TABLE_NAME = STACK_NAME + "MyTable";
  public static final String GSI_NAME = "MyGsi";
  public static final String SECOND_GSI_NAME = "MySecondGsi";
  public static final String FUNCTION_NAME = STACK_NAME + "MyFunction";
  public static final String FUNCTION_HANDLER = "software.amazon.samples.lambda.Handler";
  public static final String FUNCTION_PATH = "../lambda/target/lambda-1.0-jar-with-dependencies.jar";
  public static final String FUNCTION_ENV_VARIABLE = "TABLE_NAME";

  public static final Integer WRITE_AUTO_SCALING_MIN = 5;
  public static final Integer READ_AUTO_SCALING_MIN = 5;
  public static final Integer WRITE_AUTO_SCALING_MAX = 10;
  public static final Integer READ_AUTO_SCALING_MAX = 10;
  public static final Integer WRITE_AUTO_SCALING_UTILIZATION_TARGET = 70;
  public static final Integer READ_AUTO_SCALING_UTILIZATION_TARGET = 70;
}