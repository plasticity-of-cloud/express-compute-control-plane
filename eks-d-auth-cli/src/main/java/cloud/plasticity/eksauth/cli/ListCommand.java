package cloud.plasticity.eksauth.cli;

import cloud.plasticity.eksauth.crd.PodIdentityAssociation;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "list", description = "List pod identity associations for a cluster")
public class ListCommand implements Runnable {
    @Inject
    KubernetesClient kubernetesClient;

    @Option(names = "--cluster-name", required = true, description = "EKS cluster name")
    String clusterName;

    @Option(names = "--namespace", description = "Filter by namespace (lists all namespaces if omitted)")
    String namespace;

    @Override
    public void run() {
        var query = kubernetesClient.resources(PodIdentityAssociation.class);
        var items = namespace != null
            ? query.inNamespace(namespace).list().getItems()
            : query.inAnyNamespace().list().getItems();

        var filtered = items.stream()
            .filter(c -> clusterName.equals(c.getSpec().getClusterName()))
            .toList();

        if (filtered.isEmpty()) {
            System.out.println("No associations found for cluster: " + clusterName);
            return;
        }

        System.out.printf("%-20s %-20s %s%n", "NAMESPACE", "SERVICE ACCOUNT", "ROLE ARN");
        for (PodIdentityAssociation c : filtered) {
            System.out.printf("%-20s %-20s %s%n",
                c.getMetadata().getNamespace(),
                c.getSpec().getServiceAccount(),
                c.getSpec().getRoleArn());
        }
    }
}
