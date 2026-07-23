package ai.codriverlabs.ecp.infra;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class InfraApp {
    public static void main(String[] args) {
        App app = new App();

        // --context development=true via CDK CLI, or -Ddevelopment=true via mvn exec:java
        boolean development = "true".equals(System.getProperty("development"))
            || "true".equals(app.getNode().tryGetContext("development"));
        if (development) {
            // Override context so the stack sees it
            app.getNode().setContext("development", "true");
        }

        String account = (String) app.getNode().tryGetContext("account");
        String region = (String) app.getNode().tryGetContext("region");

        StackProps.Builder propsBuilder = StackProps.builder()
            .description("Express Compute Control Plane Service with Workload Identity for EKS-DX, EKS-D, k3s, and microk8s clusters");

        if (account != null && region != null) {
            propsBuilder.env(Environment.builder()
                .account(account).region(region).build());
        }

        new ExpressComputeControlPlaneStack(app, "ExpressComputeControlPlaneStack", propsBuilder.build());
        app.synth();
    }
}
