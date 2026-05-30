package ai.codriverlabs.eksdx.infra;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class InfraApp {
    public static void main(String[] args) {
        App app = new App();

        String account = (String) app.getNode().tryGetContext("account");
        String region = (String) app.getNode().tryGetContext("region");

        StackProps.Builder propsBuilder = StackProps.builder()
            .description("EKS-DX Service — Pod Identity for k3s, microk8s, and EKS-D clusters");

        if (account != null && region != null) {
            propsBuilder.env(Environment.builder()
                .account(account).region(region).build());
        }

        new EksDXpressControlPlaneStack(app, "EksDXpressControlPlaneStack", propsBuilder.build());
        app.synth();
    }
}
