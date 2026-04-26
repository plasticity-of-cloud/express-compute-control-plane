package cloud.plasticity.webhook;

import cloud.plasticity.eksauth.crd.PodIdentityAssociation;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Looks up Pod Identity Associations from CRD resources.
 * CRD name convention: "{clusterName}-{serviceAccount}" in the target namespace.
 */
@ApplicationScoped
public class PodIdentityAssociationLookup {

    private static final Logger LOG = Logger.getLogger(PodIdentityAssociationLookup.class);

    @Inject
    KubernetesClient kubernetesClient;

    @ConfigProperty(name = "eks.cluster-name")
    String clusterName;

    public boolean hasAssociation(String clusterName, String namespace, String serviceAccount) {
        try {
            String crdName = clusterName + "-" + serviceAccount;
            var crd = kubernetesClient.resources(PodIdentityAssociation.class)
                .inNamespace(namespace)
                .withName(crdName)
                .get();

            if (crd != null && crd.getSpec() != null) {
                LOG.debugf("CRD association found for %s/%s/%s", clusterName, namespace, serviceAccount);
                return true;
            }
        } catch (Exception e) {
            LOG.errorf("Error looking up Pod Identity association CRD: %s", e.getMessage());
        }
        return false;
    }
}
