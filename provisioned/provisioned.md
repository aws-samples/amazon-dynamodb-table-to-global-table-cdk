# How to change a DynamoDB Table with Provisioned capacity mode and auto-scaling to CfnGlobalTable

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

The following classes in the `provisioned` folder will be used in this step:

- [`software.amazon.samples.provisioned.ProvisionedApp.java`](./src/main/java/software/amazon/samples/provisioned/ProvisionedApp.java)
- [`software.amazon.samples.provisioned.ProvisionedStack0.java`](./src/main/java/software/amazon/samples/provisioned/ProvisionedStack0.java)

Run the following commands to create this stack in your own AWS account. In this step, we create a table with
Provisioned capacity mode using the Table construct, and enable auto-scaling for
the table and its GSI using: `table.autoScaleReadCapacity()`, `table.autoScaleWriteCapacity()`
, `table.autoScaleGlobalSecondaryIndexReadCapacity()`,
and `table.autoScaleGlobalSecondaryIndexWriteCapacity()`.

```
cd provisioned
mvn package
```

```
cdk --no-path-metadata --no-asset-metadata diff ProvisionedStack0 
```

After reviewing the resources that will be created for this stack, run:

```
cdk --no-path-metadata --no-asset-metadata deploy ProvisionedStack0
```

In each step, we will pass the stack id of that specific step to the CDK app during `cdk synth`, `cdk diff`,
and `cdk deploy`.

Optional - Run the lambda function to add an item to the newly created DynamoDB table, and then count how many items
exist in the table.

```
aws lambda invoke --function-name ProvisionedStackMyFunction \
out --log-type Tail --query 'LogResult' --output text |  base64 -d
```

Optional - To check the stack in your AWS account, go to `CloudFormation` -> `Stacks` -> `ProvisionedStack` in the AWS
Console.
Make sure you are in the "eu-west-1" region.

Optional - To see the synthesized CloudFormation template for each step, run:

```
cdk --no-path-metadata --no-asset-metadata synth ProvisionedStack0 > templates/step0-template.yaml
```

## Step 1 - Deregister the auto-scaling resources from the table

In this step, to prepare for changing the table resource type to CfnGlobalTable, we switch the billing mode of the table
to `PAY_PER_REQUEST` because we want to deregister the existing
auto-scaling target and policy resources from the table, GSI(s), and replica table(s).

**Why are we doing this?** For Provisioned tables, we must configure auto-scaling directly in the CfnGlobalTable
resource using
the `WriteProvisionedThroughputSettings` and `ReadProvisionedThroughputSettings` properties.
Also, there should not be additional auto-scaling policies on any of the table replicas or global secondary
indexes. That is why we need to deregister the auto-scaling resources created by Table construct for the table.

In step 7, we are going to switch back to the `PROVISIONED` mode again.
Doing so, the CfnGlobalTable will create an auto-scaling policy on each of the table
replicas to control their read and write capacities.
CfnGlobalTable will ensure that all replicas have the same write capacity auto-scaling property.

Please refer
to [`software.amazon.samples.provisioned.ProvisionedStack1.java`](./src/main/java/software/amazon/samples/provisioned/ProvisionedStack1.java)
to follow the changes.

Run these commands:

```
cdk --no-path-metadata --no-asset-metadata diff ProvisionedStack1
```

```
cdk --no-path-metadata --no-asset-metadata deploy ProvisionedStack1
```

You can follow the progress of ongoing changes to the table in the AWS Console.

More information about table behavior while switching from Provisioned to On-demand capacity mode, and also initial
throughput for On-demand capacity mode is available in
[this documentation](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.ReadWriteCapacityMode.html#HowItWorks.InitialThroughput)
.

## Step 2 - Protect the table from deletion

In this step, we are going to:

- protect the table from deletion
- protect the replica table(s) from deletion
- enable continuous backup for the table
- enable continuous backup for the replica table(s)

[`software.amazon.samples.provisioned.ProvisionedStack2.java`](./src/main/java/software/amazon/samples/provisioned/ProvisionedStack2.java)
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
cdk --no-path-metadata --no-asset-metadata diff ProvisionedStack2
```

After reviewing the changes in this stack, run:

```
cdk --no-path-metadata --no-asset-metadata deploy ProvisionedStack2
```

## Step 3 - Remove any references and dependencies to the table

Now that the table and its replicas are protected from deletion and continuous backup is
enabled, we continue by removing the dependencies to the Table resource.

[`software.amazon.samples.provisioned.ProvisionedStack3.java`](./src/main/java/software/amazon/samples/provisioned/ProvisionedStack3.java)
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
  Check `getTableStreamArn()` for implementation details.

Run:

```
cdk --no-path-metadata --no-asset-metadata diff ProvisionedStack3
```

Run:

```
cdk --no-path-metadata --no-asset-metadata deploy ProvisionedStack3
```

Optional - Run the command below to test that the lambda can access the table similar to the previous steps:

```
aws lambda invoke --function-name ProvisionedStackMyFunction \
out --log-type Tail --query 'LogResult' --output text |  base64 -d
```

## Step 4 - Detach the table from the stack

In this step, we are going to detach the table from the stack. All the required changes for this step are included in
[`software.amazon.samples.provisioned.ProvisionedStack4.java`](./src/main/java/software/amazon/samples/provisioned/ProvisionedStack4.java)
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
cdk --no-path-metadata --no-asset-metadata diff ProvisionedStack4
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
cdk --no-path-metadata --no-asset-metadata deploy ProvisionedStack4
```

Optional - Run the command below to ensure that the table and data stored in it are available:

```
aws lambda invoke --function-name ProvisionedStackMyFunction \
out --log-type Tail --query 'LogResult' --output text |  base64 -d
```

## Step 5 - Import the table as a CfnGlobalTable resource

Now that the table is detached from AWS CDK and CloudFormation, we need to import it into our stack as a CfnGlobalTable
resource.

**Do NOT run `cdk deploy` for this step.**

Following the instructions below, we are going to import the detached
table into our existing stack. As you can see in
[`software.amazon.samples.provisioned.ProvisionedStack5.java`](./src/main/java/software/amazon/samples/provisioned/ProvisionedStack5.java)
, CfnGlobalTable with the exact same configuration as `ProvisionedStackMyTable` is added to the stack.

```
// Refer to the table with its name
ITable table = Table.fromTableName(this, "MyExternalTable", TABLE_NAME);

// CfnGlobalTable
CfnGlobalTable globalTable = createGlobalTable();
```

There should be no other changes than the added global table in your stack when you run the following command:

```
cdk --no-path-metadata --no-asset-metadata diff ProvisionedStack5
```

You should see a similar diff:

```
Stack ProvisionedStack5 (ProvisionedStack)
Resources
[+] AWS::DynamoDB::GlobalTable MyGlobalTable MyGlobalTable 
```

The detached table can be imported into the stack via `cdk import` which is currently in preview.
There is also an alternative option
using [CloudFormation](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/resource-import-existing-stack.html)
.
Follow the instruction for your preferred method:

### Option 1 - Use cdk import

Using `cdk import`, we are going to import the detached table into the stack as a CfnGlobalTable resource.
Make sure that you are using cdk version `2.68.0` or higher.

Run the command below:

```
cdk --no-path-metadata --no-asset-metadata import ProvisionedStack5 
```

Confirm that the table name is correct, as below:

```
ProvisionedStack5 (ProvisionedStack)
MyGlobalTable (AWS::DynamoDB::GlobalTable): import with TableName=ProvisionedStackMyTable (yes/no) [default: yes]? yes
ProvisionedStack5 (ProvisionedStack): importing resources into stack...
ProvisionedStack: creating CloudFormation changeset...

 ✅  ProvisionedStack5 (ProvisionedStack)
```

If this command completed successfully, the table is again managed by AWS CDK as a global table.

### Option 2 - Use CloudFormation

To create a CloudFormation template representing this CDK
stack: [`software.amazon.samples.provisioned.ProvisionedStack5.java`](./src/main/java/software/amazon/samples/provisioned/ProvisionedStack5.java)
, run:

```
cdk --no-path-metadata --no-asset-metadata synth ProvisionedStack5 > templates/step5-template.yaml
```

Using AWS CLI, we create a CloudFormation change set of type `IMPORT` with the following parameters.
More information is available
in [the documentation](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/resource-import-existing-stack.html#resource-import-existing-stack-cli)
.

```
aws cloudformation create-change-set \
--stack-name ProvisionedStack --change-set-name ImportChangeSet \
--change-set-type IMPORT \
--resources-to-import "[ \
{\"ResourceType\":\"AWS::DynamoDB::GlobalTable\",\"LogicalResourceId\":\"MyGlobalTable\", \"ResourceIdentifier\":{\"TableName\":\"ProvisionedStackMyTable\"}}
]" \
--template-body file://./templates/step5-template.yaml --capabilities CAPABILITY_NAMED_IAM
```

Execute the change set to import the resources into the stack.

```
aws cloudformation execute-change-set --change-set-name ImportChangeSet --stack-name ProvisionedStack
```

If this command completed successfully, the table is again managed by AWS CDK and CloudFormation and it
has `AWS::DynamoDB::GlobalTable` resource type.
You can verify this in `CloudFormation` -> `Stacks` -> `ProvisionedStack` in the AWS Console.

## Step 6 - Add the references and dependencies to the table

In this step, we are going to clean and remove all the changes done during previous steps as well as using and
referring to the CfnGlobalTable resource.
Please refer
to [`software.amazon.samples.provisioned.ProvisionedStack6.java`](./src/main/java/software/amazon/samples/provisioned/ProvisionedStack6.java)
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
cdk --no-path-metadata --no-asset-metadata diff ProvisionedStack6
```

```
cdk --no-path-metadata --no-asset-metadata deploy ProvisionedStack6
```

## Step 7 - Switch back to Provisioned mode

In this step, we switch back the table billing mode to `PROVISIONED`.
The CfnGlobalTable resource creates auto-scaling resources for the table, GSI(s), and replica table(s) based on the
configuration in `WriteProvisionedThroughputSettings` and `ReadProvisionedThroughputSettings` properties.
Please refer
to [`software.amazon.samples.provisioned.ProvisionedStack7.java`](./src/main/java/software/amazon/samples/provisioned/ProvisionedStack7.java)
to follow the changes.

```
CfnGlobalTable.WriteProvisionedThroughputSettingsProperty writeProvisionedThroughputSettings = CfnGlobalTable.WriteProvisionedThroughputSettingsProperty.builder()
    .writeCapacityAutoScalingSettings(CfnGlobalTable.CapacityAutoScalingSettingsProperty.builder()
        .maxCapacity(WRITE_AUTO_SCALING_MAX)
        .minCapacity(WRITE_AUTO_SCALING_MIN)
        .seedCapacity(WRITE_AUTO_SCALING_MIN)
        .targetTrackingScalingPolicyConfiguration(CfnGlobalTable.TargetTrackingScalingPolicyConfigurationProperty.builder()
            .targetValue(WRITE_AUTO_SCALING_UTILIZATION_TARGET)
            .build())
        .build())
    .build();

List<CfnGlobalTable.GlobalSecondaryIndexProperty> indexes = List.of(CfnGlobalTable.GlobalSecondaryIndexProperty.builder()
    .indexName(GSI_NAME)
    ...
    .writeProvisionedThroughputSettings(writeProvisionedThroughputSettings)
    .build()
);

List<CfnGlobalTable.ReplicaSpecificationProperty> replicas = List.of(
    CfnGlobalTable.ReplicaSpecificationProperty.builder()
        .region("eu-west-1")
        .globalSecondaryIndexes(List.of(CfnGlobalTable.ReplicaGlobalSecondaryIndexSpecificationProperty.builder()
            .indexName(GSI_NAME)
            .readProvisionedThroughputSettings(CfnGlobalTable.ReadProvisionedThroughputSettingsProperty.builder()
                .readCapacityAutoScalingSettings(CfnGlobalTable.CapacityAutoScalingSettingsProperty.builder()
                    .maxCapacity(READ_AUTO_SCALING_MAX)
                    .minCapacity(READ_AUTO_SCALING_MIN)
                    .seedCapacity(READ_AUTO_SCALING_MIN)
                    .targetTrackingScalingPolicyConfiguration(CfnGlobalTable.TargetTrackingScalingPolicyConfigurationProperty.builder()
                        .targetValue(READ_AUTO_SCALING_UTILIZATION_TARGET)
                        .build())
                    .build())
                .build())
            .build()))
        ...
        .readProvisionedThroughputSettings(CfnGlobalTable.ReadProvisionedThroughputSettingsProperty.builder()
            .readCapacityAutoScalingSettings(CfnGlobalTable.CapacityAutoScalingSettingsProperty.builder()
                .maxCapacity(READ_AUTO_SCALING_MAX)
                .minCapacity(READ_AUTO_SCALING_MIN)
                .seedCapacity(READ_AUTO_SCALING_MIN)
                .targetTrackingScalingPolicyConfiguration(CfnGlobalTable.TargetTrackingScalingPolicyConfigurationProperty.builder()
                    .targetValue(READ_AUTO_SCALING_UTILIZATION_TARGET)
                    .build())
                .build())
            .build())
        .build(),
    ...        
);

CfnGlobalTable table = CfnGlobalTable.Builder.create(this, "MyGlobalTable")
    .tableName(TABLE_NAME)
    .billingMode("PROVISIONED")
    .writeProvisionedThroughputSettings(writeProvisionedThroughputSettings)
    .globalSecondaryIndexes(indexes)
    .replicas(replicas)
    ...
```

Run these commands:

```
cdk --no-path-metadata --no-asset-metadata diff ProvisionedStack7
```

```
cdk --no-path-metadata --no-asset-metadata deploy ProvisionedStack7
```

You can follow the progress of ongoing changes to the table in the AWS Console.
When the stack is deployed successfully, the change of resource type from Table to CfnGlobalTable is completed.

## Step 8 - Add a new GSI to the table

As an additional example, suppose we want to add one more GSI to the table.
Please refer
to [`software.amazon.samples.provisioned.ProvisionedStack8.java`](./src/main/java/software/amazon/samples/provisioned/ProvisionedStack8.java)
where the second GSI is added to the table.

```
// Adding a new GSI as an example
CfnGlobalTable.GlobalSecondaryIndexProperty.builder()
    .indexName(SECOND_GSI_NAME)
    ...
            
// Adding a new GSI
CfnGlobalTable.ReplicaGlobalSecondaryIndexSpecificationProperty.builder()
    .indexName(SECOND_GSI_NAME)
    ...            
```

Run these commands:

```
cdk --no-path-metadata --no-asset-metadata diff ProvisionedStack8
```

```
cdk --no-path-metadata --no-asset-metadata deploy ProvisionedStack8
```

After the command successfully completed, check the table, GSIs and its replica tables in AWS Console.

## Step 9 - Cleanup

Do not forget to delete the stack and the table from your AWS account after running this example.