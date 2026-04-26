package cloud.plasticity.eksauth.cli;

import cloud.plasticity.eksauth.crd.PodIdentityAssociation;
import cloud.plasticity.eksauth.crd.PodIdentityAssociationSpec;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "create", description = "Create a pod identity association")
public class CreateCommand implements Runnable {
    @Inject
    KubernetesClient kubernetesClient;

    @Option(names = "--cluster-name", required = true, description = "EKS cluster name")
    String clusterName;

    @Option(names = "--service-account", required = true, description = "Service account in format namespace:serviceaccount")
    String serviceAccount;

    @Option(names = "--role-arn", required = true, description = "IAM role ARN")
    String roleArn;

    @Override
    public void run() {
        String[] parts = serviceAccount.split(":");
        if (parts.length != 2) {
            System.err.println("Error: --service-account must be in format namespace:serviceaccount");
            System.exit(1);
        }
        String namespace = parts[0];
        String sa = parts[1];

        PodIdentityAssociation crd = new PodIdentityAssociation();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(clusterName + "-" + sa);
        meta.setNamespace(namespace);
        crd.setMetadata(meta);
        crd.setSpec(new PodIdentityAssociationSpec(clusterName, namespace, sa, roleArn));

        kubernetesClient.resources(PodIdentityAssociation.class)
            .inNamespace(namespace)
            .create(crd);

        System.out.printf("Created: %s/%s -> %s%n", namespace, sa, roleArn);
    }
}
