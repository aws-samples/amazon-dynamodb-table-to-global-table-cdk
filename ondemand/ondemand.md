# How to change a DynamoDB Table with On-demand capacity mode to CfnGlobalTable

**Follow all the steps below to avoid any deletion of the table, and any replica tables.
This change is done with zero downtime.**

## Step 0 - Init the example

The lambda function that is included as an example must be packaged as a jar file to be included in the CDK stack.
To learn about the lambda handler,
check [`software.amazon.samples.lambda.Handler.java`](../lambda/src/main/java/software/amazon/samples/lambda/Handler.java)
.
The handler calls putItem() to add a new item to the DynamoDB table, and then counts the existing items in the table by
calling scan().

To package the lambda function, run:

```
cd lambda
mvn package
```

The following classes in the `ondemand` folder will be used in this step:

- [`software.amazon.samples.ondemand.OnDemandApp.java`](./src/main/java/software/amazon/samples/ondemand/OnDemandApp.java)
- [`software.amazon.samples.ondemand.OnDemandStack0.java`](./src/main/java/software/amazon/samples/ondemand/OnDemandStack0.java)

Run the following commands to create this stack in your own AWS account.
In this step, we create a table with On-demand capacity mode.

```
cd ondemand
mvn package
```

```
cdk --no-path-metadata --no-asset-metadata diff OnDemandStack0 
```

After reviewing the resources that will be created for this stack, run:

```
cdk --no-path-metadata --no-asset-metadata deploy OnDemandStack0
```

In each step, we will pass the stack id of that specific step to the CDK app during `cdk synth`, `cdk diff`,
and `cdk deploy`.

Optional - Run the lambda function to add an item to the newly created DynamoDB table, and then count how many items
exist in the table.

```
aws lambda invoke --function-name OnDemandStackMyFunction \
out --log-type Tail --query 'LogResult' --output text |  base64 -d
```

Optional - To check the stack in your AWS account, go to `CloudFormation` -> `Stacks` -> `OnDemandStack` in the AWS
Console.
Make sure you are in the "eu-west-1" region.

Optional - To see the synthesized CloudFormation template for each step, run:

```
cdk --no-path-metadata --no-asset-metadata synth OnDemandStack0 > templates/step0-template.yaml
```

## Step 1 - Protect the table from deletion

In this step, we are going to:

- protect the table from deletion
- protect the replica table(s) from deletion
- enable continuous backup for the table
- enable continuous backup for the replica table(s)

[`software.amazon.samples.ondemand.OnDemandStack1.java`](src/main/java/software/amazon/samples/ondemand/OnDemandStack1.java)
covers these protections.

To protect the table and its GSI(s) from deletion during this change, it is important to
set `RemovalPolicy.RETAIN`.

```
table.applyRemovalPolicy(RemovalPolicy.RETAIN);
```

Because the replica table(s) were created using AWS SDK inside a custom resource with `Custom::DynamoDBReplica` type,
the custom resources must also be protected from deletion. This, in turn, protects the replica table(s) from deletion.

```
table.getNode().getChildren().stream()
  .filter(child -> child instanceof CustomResource)
  .forEach(customResource -> customResource.getNode().getChildren()
    .stream()
    .filter(resource -> resource instanceof CfnResource)
    .filter(cfnResource -> ((CfnResource) cfnResource).getCfnResourceType().equals("Custom::DynamoDBReplica"))
    .forEach(replica -> ((CfnResource) replica).applyRemovalPolicy(RemovalPolicy.RETAIN))
);
```

To enable continuous backup for the table, add `.pointInTimeRecovery(Boolean.TRUE)` to the table:

```
private Table createTable() {
  return Table.Builder.create(this, "MyTable")
    ...
    // Enable PiTR for the table
    .pointInTimeRecovery(Boolean.TRUE)
    .build();
}
```

To enable continuous backup for the replica table(s) that were created using AWS SDK inside the custom resource(s)
with `Custom::DynamoDBReplica` type,
we create the following custom resource that should be executed **for each replica table**.

```
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
    .onUpdate(updateContinuousBackups)
    .policy(AwsCustomResourcePolicy.fromSdkCalls(SdkCallsPolicyOptions.builder()
        .resources(List.of(buildTableArn(replicaRegion)))
        .build()))
    .build();
}
```

To inspect the changes to the stack in this step, run:

```
cdk --no-path-metadata --no-asset-metadata diff OnDemandStack1
```

After reviewing the changes in this stack, run:

```
cdk --no-path-metadata --no-asset-metadata deploy OnDemandStack1
```

## Step 2 - Remove any references and dependencies to the table

Now that the table and its replicas are protected from deletion and continuous backup is enabled,
we continue by removing the dependencies to the Table resource.

[`software.amazon.samples.ondemand.OnDemandStack2.java`](./src/main/java/software/amazon/samples/ondemand/OnDemandStack2.java)
covers the following changes.

- To remove the references to the table from the lambda function, use `Table.fromTableName()` as shown below:

```
// Table
// To prepare removing the Table construct from the stack in the next step
// Use a new variable to refer to the table created in the previous steps
Table myTable = createTable();
...

// To prepare removing the Table construct from the stack in the next step
// Refer to the table with its name and a new construct id
ITable table = Table.fromTableName(this, "MyExternalTable", TABLE_NAME);
...

// Using ITable instead of Table
private void createFunction(ITable table) {
  Function lambda = new Function(this, "MyFunction", FunctionProps.builder()
      .environment(Map.of(FUNCTION_ENV_VARIABLE, table.getTableName()))
      ...        
```

- Output - TableStreamArn: Create a custom resource that calls `describeTable()` using AWS SDK to get the ARN of table
  DynamoDB Stream.

```
// Output
// Using a custom resource to get the ARN of table stream using AWS SDK - describeTable()
// outputTableStreamArn(table);
outputTableStreamArn(getTableStreamArn());
```

```
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

private String buildTableArn(String region) {
  return "arn:" + Aws.PARTITION + ":dynamodb:" + region + ":" + Aws.ACCOUNT_ID + ":table/" + TABLE_NAME;
}
```

Run:

```
cdk --no-path-metadata --no-asset-metadata diff OnDemandStack2
```

Run:

```
cdk --no-path-metadata --no-asset-metadata deploy OnDemandStack2
```

Optional - Run the command below to test that the lambda can access the table similar to the previous steps:

```
aws lambda invoke --function-name OnDemandStackMyFunction \
out --log-type Tail --query 'LogResult' --output text |  base64 -d
```

## Step 3 - Detaching the table from the stack

In this step, we are going to detach the table from the stack. All the required changes for this step are included in
[`software.amazon.samples.ondemand.OnDemandStack3.java`](./src/main/java/software/amazon/samples/ondemand/OnDemandStack3.java)
.

By commenting out the table from the stack, it gets detached from AWS CDK and CloudFormation.
Because of the protections performed in the previous steps, the table will be considered orphaned but it will not get
deleted.

**It is VERY IMPORTANT that the previous steps were completed successfully.**

```
// Table
// Comment out the Table to detach it from the stack
// Table myTable = createTable();
// createGsi(myTable);

// Protect table and replica table(s) from deletion
// protectTableFromDeletion(myTable);

// Enable PiTR for the replica table
// You need to enable PiTR for all the replica tables.
// for (String replicaRegion : REPLICA_REGIONS) {
//   pointInTimeRecoveryForReplicaTable(replicaRegion);
// }

// Refer to the table with its name
ITable table = Table.fromTableName(this, "MyExternalTable", TABLE_NAME);
```

Run:

```
cdk --no-path-metadata --no-asset-metadata diff OnDemandStack3
```

You should see similar diff for this stack where the table and replica table become **orphan**
instead of *destroy*, otherwise they will get deleted.
If the diff indicates that the table or its replica table will be destroyed, **do not proceed**.

```
Resources
[-] AWS::DynamoDB::Table MyTable794ED orphan
[-] Custom::DynamoDBReplica MyTableReplicaeuwest13DC3D orphan
[-] Custom::DynamoDBReplica MyTableReplicaeunorth196814 orphan
...
```

**Before running this step, make sure that you have not skipped any of the previous steps.**

```
cdk --no-path-metadata --no-asset-metadata deploy OnDemandStack3
```

Optional - Run the command below to ensure that the table and data stored in it are available:

```
aws lambda invoke --function-name OnDemandStackMyFunction \
out --log-type Tail --query 'LogResult' --output text |  base64 -d
```

## Step 4 - Importing the table as a CfnGlobalTable resource

Now that the table is detached from AWS CDK and CloudFormation, we need to import it into our stack as a CfnGlobalTable
resource.

**Do NOT run `cdk deploy` for this step.**

Following the instructions below, we are going to import the detached
table into our existing stack. As you can see in
[`software.amazon.samples.ondemand.OnDemandStack4.java`](src/main/java/software/amazon/samples/ondemand/OnDemandStack4.java)
, CfnGlobalTable with the exact same configuration as `OnDemandStackMyTable` is added to the stack.

```
// Refer to the table with its name
ITable table = Table.fromTableName(this, "MyExternalTable", TABLE_NAME);

// CfnGlobalTable
CfnGlobalTable globalTable = createGlobalTable();
```

There should be no other changes than the added global table in your stack when you run the following command:

```
cdk --no-path-metadata --no-asset-metadata diff OnDemandStack4
```

You should see a similar diff:

```
Stack OnDemandStack4 (OnDemandStack)
Resources
[+] AWS::DynamoDB::GlobalTable MyGlobalTable MyGlobalTable 
```

The detached table can be imported into the stack via `cdk import` which is currently in preview.
There is also an alternative option
using [CloudFormation](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/resource-import-existing-stack.html)
.
Follow the instruction for your preferred method:

### Option 1 - Using cdk import

Using `cdk import`, we are going to import the detached table into the stack as a CfnGlobalTable resource.
While [this issue](https://github.com/aws/aws-cdk/pull/24439) is still open, we need to apply the following workaround
to
import the table.
Run the command below, it asks you to provide Arn and StreamArn. Enter any text, for example "arn", to both of these
questions.

```
cdk --no-path-metadata --no-asset-metadata import OnDemandStack4 --record-resource-mapping=table-identifiers.json
```

You see the following json in `table-identifiers.json` file:

```
{
  "MyGlobalTable": {
    "TableName": "OnDemandStackMyTable",
    "Arn": "arn",
    "StreamArn": "arn"
  }
}
```

Edit this file and remove the two lines for Arn and StreamArn. This file should look like as below:

```
{
  "MyGlobalTable": {
    "TableName": "OnDemandStackMyTable"
  }
}
```

Now, run the following command to import the table into your stack:

```
cdk --no-path-metadata --no-asset-metadata import OnDemandStack4 --resource-mapping=table-identifiers.json
```

If this command completed successfully, the table is again managed by AWS CDK as a CfnGlobalTable resource.

### Option 2 - Using CloudFormation

To create a CloudFormation template representing this CDK
stack: [`software.amazon.samples.ondemand.OnDemandStack4.java`](src/main/java/software/amazon/samples/ondemand/OnDemandStack4.java)
, run:

```
cdk --no-path-metadata --no-asset-metadata synth OnDemandStack4 > templates/step4-template.yaml
```

Using AWS CLI, we create a CloudFormation change set of type `IMPORT` with the following parameters.
More information is available
in [the documentation](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/resource-import-existing-stack.html#resource-import-existing-stack-cli)
.

```
aws cloudformation create-change-set \
--stack-name OnDemandStack --change-set-name ImportChangeSet \
--change-set-type IMPORT \
--resources-to-import "[ \
{\"ResourceType\":\"AWS::DynamoDB::GlobalTable\",\"LogicalResourceId\":\"MyGlobalTable\", \"ResourceIdentifier\":{\"TableName\":\"OnDemandStackMyTable\"}}
]" \
--template-body file://./templates/step4-template.yaml --capabilities CAPABILITY_NAMED_IAM
```

Execute the change set to import the resources into the stack.

```
aws cloudformation execute-change-set --change-set-name ImportChangeSet --stack-name OnDemandStack
```

If this command completed successfully, the table is again managed by AWS CDK and CloudFormation and it
has `AWS::DynamoDB::GlobalTable` resource type.
You can verify this in `CloudFormation` -> `Stacks` -> `OnDemandStack` in the AWS Console.

## Step 5 - Add the references and dependencies to the table

In this step, we are going to clean and remove all the changes done during previous steps as well as using and
referring to the CfnGlobalTable resource.
Please refer
to [`software.amazon.samples.ondemand.OnDemandStack5.java`](src/main/java/software/amazon/samples/ondemand/OnDemandStack5.java)
to follow the changes.

```
// Using CfnGlobalTable resource instead of referring to the table with its name
// ITable table = Table.fromTableName(this, "MyExternalTable", TABLE_NAME);

// CfnGlobalTable
CfnGlobalTable globalTable = createGlobalTable();

// Lambda
createFunction(globalTable);

// Output
// Updated to use CfnGlobalTable as input - calls table.getAttrStreamArn() instead of using a custom resource to get the ARN of table stream using getTableStreamArn()
outputTableStreamArn(globalTable);
```

Run these commands:

```
cdk --no-path-metadata --no-asset-metadata diff OnDemandStack5
```

```
cdk --no-path-metadata --no-asset-metadata deploy OnDemandStack5
```

When the stack is deployed successfully, the change of resource type from Table to CfnGlobalTable is completed.

## Step 6 - Add a new replica table to the table

As an additional example, suppose we want to add one more replica table to the CfnGlobalTable resource.
Let us add a replica table in "eu-central-1" region. Please refer
to [`software.amazon.samples.ondemand.OnDemandStack6.java`](./src/main/java/software/amazon/samples/ondemand/OnDemandStack6.java)
.
Because we are using CfnGlobalTable, we simply need to add a new replication region to the `replicas()`.

Run:

```
cdk --no-path-metadata --no-asset-metadata diff OnDemandStack6
```

Run the following command to deploy the change:

```
cdk --no-path-metadata --no-asset-metadata deploy OnDemandStack6
```

As you can see in the AWS Console, `CloudFormation` -> `Stacks` -> `OnDemandStack` in "eu-west-1" region,
there is no custom resource used in the stack for adding this new replica table.

## Step 7 - Cleanup

Do not forget to delete the stack and the table from your AWS account after running this example.