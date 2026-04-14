package com.plcloud.eksauth.cli;

import com.plcloud.eksauth.model.PodIdentityAssociation;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import picocli.commandline.Command;
import picocli.commandline.Option;

@Command(name = "list", description = "List pod identity associations")
@RegisterForReflection
public class ListCommand implements Runnable {
    @Inject
    KubernetesClient kubernetesClient;

    @Option(names = "--cluster-name", required = true, description = "EKS cluster name")
    String clusterName;

    @Option(names = "--namespace", description = "Kubernetes namespace (optional, lists all if not specified)")
    String namespace;

    @Override
    public void run() {
        try {
            var query = kubernetesClient.customResource(PodIdentityAssociation.class);
            
            var items = namespace != null 
                ? query.inNamespace(namespace).list().getItems()
                : query.inAnyNamespace().list().getItems();

            var filtered = items.stream()
                .filter(crd -> crd.getSpec().getClusterName().equals(clusterName))
                .toList();

            if (filtered.isEmpty()) {
                System.out.println("No pod identity associations found for cluster: " + clusterName);
                return;
            }

            System.out.println("Pod Identity Associations for cluster: " + clusterName);
            System.out.println("NAMESPACE\t\tSERVICE ACCOUNT\t\tROLE ARN");
            System.out.println("=========\t\t===============\t\t========");
            
            for (PodIdentityAssociation crd : filtered) {
                System.out.printf("%s\t\t%s\t\t%s%n",
                    crd.getMetadata().getNamespace(),
                    crd.getSpec().getServiceAccount(),
                    crd.getSpec().getRoleArn()
                );
            }
        } catch (Exception e) {
            System.err.println("Error listing pod identity associations: " + e.getMessage());
            System.exit(1);
        }
    }
}
