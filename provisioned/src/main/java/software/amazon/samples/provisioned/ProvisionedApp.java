// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package software.amazon.samples.provisioned;

import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;

import static software.amazon.samples.provisioned.StepConfig.STACK_NAME;

public final class ProvisionedApp {
  public static void main(final String[] args) {
    App app = new App();
    StackProps myStackProps = StackProps.builder().stackName(STACK_NAME).build();
    new ProvisionedStack0(app, STACK_NAME + 0, myStackProps);
    new ProvisionedStack1(app, STACK_NAME + 1, myStackProps);
    new ProvisionedStack2(app, STACK_NAME + 2, myStackProps);
    new ProvisionedStack3(app, STACK_NAME + 3, myStackProps);
    new ProvisionedStack4(app, STACK_NAME + 4, myStackProps);
    new ProvisionedStack5(app, STACK_NAME + 5, myStackProps);
    new ProvisionedStack6(app, STACK_NAME + 6, myStackProps);
    new ProvisionedStack7(app, STACK_NAME + 7, myStackProps);
    new ProvisionedStack8(app, STACK_NAME + 8, myStackProps);
    app.synth();
  }
}