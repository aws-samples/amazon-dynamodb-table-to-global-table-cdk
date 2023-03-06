// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package software.amazon.samples.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.util.HashMap;
import java.util.Map;

// This handler is for test purpose only.
public class Handler implements RequestHandler<Map<String, String>, String> {
  private static final String ENV_TABLE_NAME = "TABLE_NAME";
  private DynamoDbClient ddbClient = DynamoDbClient.builder()
      .region(Region.of(System.getenv("AWS_REGION")))
      .build();

  public String handleRequest(Map<String, String> event, Context context) {
    LambdaLogger logger = context.getLogger();
    String response = "200 OK";
    String tableName = System.getenv(ENV_TABLE_NAME);
    logger.log("*** Adding new item to " + tableName + " table.\n");
    addItem(tableName);
    Integer count = countItems(tableName);
    logger.log("*** There are " + count + " item(s) in " + tableName + " table.\n");
    return response;
  }

  private void addItem(String tableName) {
    long now = System.currentTimeMillis();
    AttributeValue partitionKeyAttr = AttributeValue.builder().s("pk#" + now).build();
    AttributeValue sortKeyAttr = AttributeValue.builder().s("sk#" + now ).build();
    Map<String, AttributeValue> attribute = new HashMap<>();
    attribute.put("PK", partitionKeyAttr);
    attribute.put("SK", sortKeyAttr);
    ddbClient.putItem(PutItemRequest.builder()
        .tableName(tableName)
        .item(attribute)
        .build()
    );
  }

  private Integer countItems(String tableName) {
    return ddbClient.scan(ScanRequest.builder().tableName(tableName).build()).count();
  }
}