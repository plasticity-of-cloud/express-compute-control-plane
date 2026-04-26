package cloud.plasticity.eksauth.service;

import cloud.plasticity.eksauth.crd.PodIdentityAssociation;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.DescribePodIdentityAssociationRequest;
import software.amazon.awssdk.services.eks.model.ListPodIdentityAssociationsRequest;
import software.amazon.awssdk.services.eks.model.PodIdentityAssociationSummary;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PodIdentityAssociationService {

    private static final Logger LOG = Logger.getLogger(PodIdentityAssociationService.class);

    @Inject
    EksClient eksClient;

    @Inject
    KubernetesClient kubernetesClient;

    // For testing
    void setEksClient(EksClient eksClient) { this.eksClient = eksClient; }
    void setKubernetesClient(KubernetesClient kubernetesClient) { this.kubernetesClient = kubernetesClient; }

    public String getRoleArnForServiceAccount(String clusterName, String namespace, String serviceAccount) {
        // 1. Primary: Kubernetes CRD
        String roleArn = getRoleArnFromCrd(clusterName, namespace, serviceAccount);
        if (roleArn != null) {
            return roleArn;
        }

        // 2. Fallback: AWS EKS API (for managed EKS clusters)
        try {
            List<PodIdentityAssociationSummary> associations = eksClient.listPodIdentityAssociations(
                ListPodIdentityAssociationsRequest.builder()
                    .clusterName(clusterName)
                    .namespace(namespace)
                    .serviceAccount(serviceAccount)
                    .build()
            ).associations();

            if (!associations.isEmpty()) {
                String associationId = associations.get(0).associationId();
                roleArn = eksClient.describePodIdentityAssociation(
                    DescribePodIdentityAssociationRequest.builder()
                        .clusterName(clusterName)
                        .associationId(associationId)
                        .build()
                ).association().roleArn();
                LOG.infof("EKS API association found for %s/%s/%s -> %s", clusterName, namespace, serviceAccount, roleArn);
                return roleArn;
            }
        } catch (Exception e) {
            LOG.debugf("EKS API lookup failed: %s", e.getMessage());
        }

        // 3. Last resort: generated default
        return getDefaultRoleArn(namespace, serviceAccount);
    }

    private String getRoleArnFromCrd(String clusterName, String namespace, String serviceAccount) {
        try {
            String crdName = clusterName + "-" + serviceAccount;
            var crd = kubernetesClient.resources(PodIdentityAssociation.class)
                .inNamespace(namespace)
                .withName(crdName)
                .get();

            if (crd != null && crd.getSpec() != null) {
                String roleArn = crd.getSpec().getRoleArn();
                LOG.infof("CRD association found for %s/%s/%s -> %s", clusterName, namespace, serviceAccount, roleArn);
                return roleArn;
            }
        } catch (Exception e) {
            LOG.debugf("CRD lookup failed: %s", e.getMessage());
        }
        return null;
    }

    private String getDefaultRoleArn(String namespace, String serviceAccount) {
        String accountId = Optional.ofNullable(System.getenv("AWS_ACCOUNT_ID"))
                .orElse("123456789012");
        return String.format("arn:aws:iam::%s:role/eks-pod-identity-%s-%s", accountId, namespace, serviceAccount);
    }

    public String generateAssociationId(String clusterName, String namespace, String serviceAccount) {
        return String.format("assoc-%s-%s-%s-%d", clusterName, namespace, serviceAccount, System.currentTimeMillis());
    }
}
