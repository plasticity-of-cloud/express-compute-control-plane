package com.plcloud.eksauth.cli;

import com.plcloud.eksauth.model.PodIdentityAssociation;
import com.plcloud.eksauth.model.PodIdentityAssociationSpec;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import picocli.commandline.Command;
import picocli.commandline.Option;

@Command(name = "create", description = "Create a pod identity association")
@RegisterForReflection
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
            System.err.println("Error: service-account must be in format namespace:serviceaccount");
            System.exit(1);
        }

        String namespace = parts[0];
        String sa = parts[1];
        String crdName = clusterName + "-" + sa;

        try {
            PodIdentityAssociation crd = new PodIdentityAssociation();
            
            ObjectMeta metadata = new ObjectMeta();
            metadata.setName(crdName);
            metadata.setNamespace(namespace);
            crd.setMetadata(metadata);

            PodIdentityAssociationSpec spec = new PodIdentityAssociationSpec(
                clusterName, namespace, sa, roleArn
            );
            crd.setSpec(spec);

            kubernetesClient.customResource(PodIdentityAssociation.class)
                .inNamespace(namespace)
                .create(crd);

            System.out.println("Pod identity association created successfully");
            System.out.printf("  Cluster: %s%n", clusterName);
            System.out.printf("  Namespace: %s%n", namespace);
            System.out.printf("  Service Account: %s%n", sa);
            System.out.printf("  Role ARN: %s%n", roleArn);
        } catch (Exception e) {
            System.err.println("Error creating pod identity association: " + e.getMessage());
            System.exit(1);
        }
    }
}
