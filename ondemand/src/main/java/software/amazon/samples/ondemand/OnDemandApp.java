// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package software.amazon.samples.ondemand;

import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;

import static software.amazon.samples.ondemand.StepConfig.STACK_NAME;

public final class OnDemandApp {
  public static void main(final String[] args) {
    App app = new App();
    StackProps myStackProps = StackProps.builder().stackName(STACK_NAME).build();
    new OnDemandStack0(app, STACK_NAME + 0, myStackProps);
    new OnDemandStack1(app, STACK_NAME + 1, myStackProps);
    new OnDemandStack2(app, STACK_NAME + 2, myStackProps);
    new OnDemandStack3(app, STACK_NAME + 3, myStackProps);
    new OnDemandStack4(app, STACK_NAME + 4, myStackProps);
    new OnDemandStack5(app, STACK_NAME + 5, myStackProps);
    new OnDemandStack6(app, STACK_NAME + 6, myStackProps);
    app.synth();
  }
}