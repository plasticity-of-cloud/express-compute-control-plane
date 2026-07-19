package ai.codriverlabs.ecp.karpenter.reconciler;

import ai.codriverlabs.ecp.karpenter.model.Ec2NodeClass;
import ai.codriverlabs.ecp.karpenter.service.ValidationConditionService;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Objects;

/**
 * Watches EC2NodeClass resources via a Fabric8 SharedIndexInformer and patches
 * {@code status.conditions[ValidationSucceeded=True]} on CREATE/UPDATE.
 *
 * Replaces the fragile kubectl proxy + curl PATCH from configure-nodepools.sh.
 * Uses quarkus-kubernetes-client directly — no quarkus-operator-sdk dependency needed.
 */
@ApplicationScoped
public class Ec2NodeClassReconciler {

    private static final Logger LOG = Logger.getLogger(Ec2NodeClassReconciler.class);

    @Inject KubernetesClient client;
    @Inject ValidationConditionService validationConditionService;

    void onStart(@Observes StartupEvent ev) {
        client.resources(Ec2NodeClass.class)
            .inAnyNamespace()
            .inform(new ResourceEventHandler<>() {
                @Override
                public void onAdd(Ec2NodeClass nc) { reconcile(nc.getMetadata().getName()); }

                @Override
                public void onUpdate(Ec2NodeClass old, Ec2NodeClass nc) {
                    // Skip status-only updates (generation unchanged) to avoid feedback loop:
                    // our own patchStatus() triggers onUpdate which would re-trigger patchStatus indefinitely.
                    if (Objects.equals(old.getMetadata().getGeneration(), nc.getMetadata().getGeneration())) {
                        LOG.debugf("EC2NodeClass/%s: status-only update (gen=%d), skipping",
                            nc.getMetadata().getName(), nc.getMetadata().getGeneration());
                        return;
                    }
                    reconcile(nc.getMetadata().getName());
                }

                @Override
                public void onDelete(Ec2NodeClass nc, boolean deletedFinalStateUnknown) {}
            });
        LOG.info("EC2NodeClass informer started");
    }

    void reconcile(String name) {
        // Always re-read from the server to get the latest resourceVersion,
        // avoiding 409 Conflict on concurrent reconcile calls.
        Ec2NodeClass nc = client.resources(Ec2NodeClass.class).withName(name).get();
        if (nc == null) return;

        if (validationConditionService.isValidationSucceeded(nc)) {
            LOG.debugf("EC2NodeClass/%s already ValidationSucceeded=True — no-op", name);
            return;
        }
        validationConditionService.setValidationSucceeded(nc);
        try {
            client.resources(Ec2NodeClass.class).resource(nc).patchStatus();
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409) {
                LOG.debugf("EC2NodeClass/%s: 409 conflict on patchStatus, will retry on next event", name);
                return;
            }
            throw e;
        }
    }
}
