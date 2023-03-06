// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package software.amazon.samples.ondemand;

import java.util.List;

public class StepConfig {
  public static final String AWS_DEFAULT_REGION = "eu-west-1";
  public static final String REPLICATION_REGION_1 = "eu-north-1";
  private static final String REPLICATION_REGION_2 = "eu-central-1";
  public static final List<String> REPLICA_REGIONS = List.of(REPLICATION_REGION_1);
  public static final List<String> DEFAULT_AND_ONE_REPLICA_REGION = List.of(AWS_DEFAULT_REGION, REPLICATION_REGION_1);
  public static final List<String> DEFAULT_AND_TWO_REPLICA_REGIONS = List.of(AWS_DEFAULT_REGION, REPLICATION_REGION_1, REPLICATION_REGION_2);
  public static final String STACK_NAME = "OnDemandStack";

  public static final String TABLE_NAME = STACK_NAME + "MyTable";
  public static final String GSI_NAME = "MyGsi";
  public static final String FUNCTION_NAME = STACK_NAME + "MyFunction";
  public static final String FUNCTION_HANDLER = "software.amazon.samples.lambda.Handler";
  public static final String FUNCTION_PATH = "../lambda/target/lambda-1.0-jar-with-dependencies.jar";
  public static final String FUNCTION_ENV_VARIABLE = "TABLE_NAME";
}