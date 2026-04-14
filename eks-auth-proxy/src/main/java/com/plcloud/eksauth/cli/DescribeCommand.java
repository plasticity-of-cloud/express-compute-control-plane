package com.plcloud.eksauth.cli;

import com.plcloud.eksauth.model.PodIdentityAssociation;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import picocli.commandline.Command;
import picocli.commandline.Option;

@Command(name = "describe", description = "Describe a pod identity association")
@RegisterForReflection
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
            System.err.println("Error: service-account must be in format namespace:serviceaccount");
            System.exit(1);
        }

        String namespace = parts[0];
        String sa = parts[1];
        String crdName = clusterName + "-" + sa;

        try {
            PodIdentityAssociation crd = kubernetesClient.customResource(PodIdentityAssociation.class)
                .inNamespace(namespace)
                .withName(crdName)
                .get();

            if (crd == null) {
                System.err.println("Pod identity association not found");
                System.exit(1);
            }

            System.out.println("Pod Identity Association:");
            System.out.printf("  Name: %s%n", crd.getMetadata().getName());
            System.out.printf("  Namespace: %s%n", crd.getMetadata().getNamespace());
            System.out.printf("  Cluster: %s%n", crd.getSpec().getClusterName());
            System.out.printf("  Service Account: %s%n", crd.getSpec().getServiceAccount());
            System.out.printf("  Role ARN: %s%n", crd.getSpec().getRoleArn());
            System.out.printf("  Created: %s%n", crd.getMetadata().getCreationTimestamp());
        } catch (Exception e) {
            System.err.println("Error describing pod identity association: " + e.getMessage());
            System.exit(1);
        }
    }
}
