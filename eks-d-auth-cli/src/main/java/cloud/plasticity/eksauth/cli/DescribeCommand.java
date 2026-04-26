package cloud.plasticity.eksauth.cli;

import cloud.plasticity.eksauth.crd.PodIdentityAssociation;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "describe", description = "Describe a pod identity association")
public class DescribeCommand implements Runnable {
    @Inject
    KubernetesClient kubernetesClient;

    @Option(names = "--cluster-name", required = true, description = "EKS cluster name")
    String clusterName;

    @Option(names = "--service-account", required = true, description = "Service account in format namespace:serviceaccount")
    String serviceAccount;

    @Override
    public void run() {
        String[] parts = serviceAccount.split(":");
        if (parts.length != 2) {
            System.err.println("Error: --service-account must be in format namespace:serviceaccount");
            System.exit(1);
        }
        String namespace = parts[0];
        String sa = parts[1];

        PodIdentityAssociation crd = kubernetesClient.resources(PodIdentityAssociation.class)
            .inNamespace(namespace)
            .withName(clusterName + "-" + sa)
            .get();

        if (crd == null) {
            System.err.println("Not found");
            System.exit(1);
        }

        System.out.printf("Cluster:         %s%n", crd.getSpec().getClusterName());
        System.out.printf("Namespace:       %s%n", crd.getMetadata().getNamespace());
        System.out.printf("Service Account: %s%n", crd.getSpec().getServiceAccount());
        System.out.printf("Role ARN:        %s%n", crd.getSpec().getRoleArn());
        System.out.printf("Created:         %s%n", crd.getMetadata().getCreationTimestamp());
    }
}
