package com.plcloud.eksauth.cli;

import com.plcloud.eksauth.model.PodIdentityAssociation;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import picocli.commandline.Command;
import picocli.commandline.Option;

@Command(name = "delete", description = "Delete a pod identity association")
@RegisterForReflection
public class DeleteCommand implements Runnable {
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
            System.err.println("Error: service-account must be in format namespace:serviceaccount");
            System.exit(1);
        }

        String namespace = parts[0];
        String sa = parts[1];
        String crdName = clusterName + "-" + sa;

        try {
            boolean deleted = kubernetesClient.customResource(PodIdentityAssociation.class)
                .inNamespace(namespace)
                .withName(crdName)
                .delete();

            if (deleted) {
                System.out.println("Pod identity association deleted successfully");
            } else {
                System.err.println("Pod identity association not found");
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("Error deleting pod identity association: " + e.getMessage());
            System.exit(1);
        }
    }
}
