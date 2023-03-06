# How to change a DynamoDB Table to CfnGlobalTable

In this repository, we are going to walk you through step-by-step instructions on how to change an existing DynamoDB table created using 
[Table](https://docs.aws.amazon.com/cdk/api/v2/java/software/amazon/awscdk/services/dynamodb/Table.html) L2 construct
to [CfnGlobalTable](https://docs.aws.amazon.com/cdk/api/v2/java/index.html?software/amazon/awscdk/services/dynamodb/CfnGlobalTable.html) without any data loss.
You can deploy the [example applications](#step-by-step-examples) under this repository in your own AWS account.

## What problem am I solving?

If you already have a DynamoDB table created using Table construct, change the resource type from Table to CfnGlobalTable when you want to:

- use the resource type that is intended for global tables.
- add a new replica table to an existing table.
- add a new GSI to an existing table with Provisioned capacity mode and auto-scaling enabled. Doing so,
you solve the issue that occurs [when adding a new GSI with auto-scaling to an existing Table with more than one replica regions](https://github.com/aws/aws-cdk/issues/23217).

## Before you  begin

What you need to know before changing to CfnGlobalTable: 
- **You cannot directly change** a resource of type [Table](https://docs.aws.amazon.com/cdk/api/v2/java/software/amazon/awscdk/services/dynamodb/Table.html) or [CfnTable](https://docs.aws.amazon.com/cdk/api/v2/java/software/amazon/awscdk/services/dynamodb/CfnTable.html) into a resource of type [CfnGlobalTable](https://docs.aws.amazon.com/cdk/api/v2/java/index.html?software/amazon/awscdk/services/dynamodb/CfnGlobalTable.html) by changing its type in your stack.
Doing so might result in **deletion** of your DynamoDB table.
- A global table must have throughput capacity configured in one of two ways: **Provisioned capacity mode with auto-scaling** or **On-demand** capacity mode.

### When creating a new DynamoDB table

If you intend to create a new DynamoDB global table with multiple replica tables, use [CfnGlobalTable](https://docs.aws.amazon.com/cdk/api/v2/java/index.html?software/amazon/awscdk/services/dynamodb/CfnGlobalTable.html) from the start.

If you intend to create a single region table now but are going to expand into more AWS Regions later, you can use CfnGlobalTable.
When you start with a single region table using CfnGlobalTable, your table will be billed the same as a single region table.
When you later update the stack to add other regions, then global tables pricing will apply.
There are differences between DynamoDB table and global tables, for example global tables can have either On-demand or Provisioned capacity mode with auto-scaling while Tables can have On-demand, Provisioned with or without auto-scaling.
For more information, see [DynamoDB global tables](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/GlobalTables.html).

### What is CfnGlobalTable?

CfnGlobalTable is an L1 construct for [`AWS::DynamoDB::GlobalTable`](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/aws-resource-dynamodb-globaltable.html).
The `AWS::DynamoDB::GlobalTable` resource enables you to create and manage a Version 2019.11.21 global table.

**This resource cannot be used to create or manage a Version 2017.11.29 global table.**

To find out which version of global table you are using, follow the instructions in [this documentation](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/globaltables.DetermineVersion.html).

### About Table construct

An example of a single region table created using the [Table](https://docs.aws.amazon.com/cdk/api/v2/java/software/amazon/awscdk/services/dynamodb/Table.html) L2 construct can be similar to the following example.
Suppose the stack containing this Table resource is deployed in "eu-west-1" region:

```
Table table = Table.Builder.create(this, "MyTable")
    .tableName("MyTable")
    .partitionKey(Attribute.builder().name("PK").type(AttributeType.STRING).build())
    .sortKey(Attribute.builder().name("SK").type(AttributeType.STRING).build())
    .billingMode(BillingMode.PAY_PER_REQUEST)
    .build();
```

You can either include or skip `replicationRegions()` for the Table construct when creating a single region table.
So, an alternative could look like this:

```
Table table = Table.Builder.create(this, "MyTable")
    .tableName("MyTable")
    ...
    .replicationRegions(List.of("eu-west-1"))
    .build();
```

Suppose you have a Table resource in "eu-west-1" and a replica table in “eu-north-1”. 
Prior to [CfnGlobalTable](https://docs.aws.amazon.com/cdk/api/v2/java/index.html?software/amazon/awscdk/services/dynamodb/CfnGlobalTable.html),
you would use the Table construct in either of the following ways:

Either the region that the table is created in, "eu-west-1", is not part of `replicationRegions()`:

```
Table table = Table.Builder.create(this, "MyTable")
    .tableName("MyTable")
    ...
    .replicationRegions(List.of("eu-north-1"))
    .build();
```

Or both regions are provided in `replicationRegions()`:

```
Table table = Table.Builder.create(this, "MyTable")
    .tableName("MyTable")
    ...
    .replicationRegions(List.of("eu-west-1", "eu-north-1"))
    .build();
```

When `replicationRegions()` is included in the Table resource, for each provided replication region,
CDK creates a custom resource that calls `UpdateTable` with `ReplicaUpdates` using **AWS SDK**. 
Depending on what event triggers the custom resource, for example: create, or delete, the `ReplicaUpdates` action updates the DynamoDB table.
Although the custom resource is managed by AWS CDK and AWS CloudFormation, **the replica tables are not managed because they were created in the custom resource using AWS SDK**.

By running `cdk synth`, you get the synthesized CloudFormation template where you find your table, `AWS::DynamoDB::Table`, and the custom resource with `Custom::DynamoDBReplica` type for each region. 
To dive deep into this custom resource, check: [replica-handler](https://github.com/aws/aws-cdk/blob/main/packages/%40aws-cdk/aws-dynamodb/lib/replica-handler/index.ts).

## About this repository

There are two folders:
* `ondemand` and `provisioned` folders contain examples for each capacity mode. Each example has a CDK stack that we use to explain this change in a step-wise approach.
  If the stack is deployed successfully, there will be a DynamoDB table with two replication regions in "eu-west-1" and "eu-north-1".
  There is also a lambda function, as well as an output for `TableStreamArn` to exemplify when there are dependencies to the table in a stack.

* `lambda` folder contains the implementation for a lambda that you can optionally run to verify the availability of the table and its data.

This repository is based on: Java 11, Maven, [CDK](https://docs.aws.amazon.com/cdk/v2/guide/cli.html), [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html).
To successfully run the examples in your own environment, please install and configure these tools.

## Step-by-step examples

Before changing your own DynamoDB table into global table, you can try out running the examples in this repository.
You can then apply the same steps on your own tables. Below, you can find the instructions for:

- [an example table with On-demand capacity mode](./ondemand/ondemand.md)
- [an example table with Provisioned capacity mode with auto-scaling](./provisioned/provisioned.md)

After the change completed successfully, we exemplify how to add:
* a second GSI to the table in the Provisioned mode example.
* a new replica table in "eu-central-1" to the table in the On-demand mode example.

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This repository is licensed under the MIT-0 License. See the [LICENSE](./LICENSE) file.