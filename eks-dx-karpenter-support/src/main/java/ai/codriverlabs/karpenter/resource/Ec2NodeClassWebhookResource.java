package ai.codriverlabs.karpenter.resource;

import ai.codriverlabs.karpenter.model.Ec2NodeClass;
import ai.codriverlabs.karpenter.model.Ec2NodeClassSpec;
import ai.codriverlabs.karpenter.service.ClusterIdentityService;
import ai.codriverlabs.karpenter.service.UserDataMergeService;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import io.javaoperatorsdk.webhook.admission.AdmissionController;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

/**
 * Mutating admission webhook for Karpenter EC2NodeClass (CREATE and UPDATE).
 *
 * <p>Merges cluster bootstrap fields (apiServerEndpoint, certificateAuthority, serviceCidr,
 * clusterDnsIp, clusterName) into {@code spec.userData}, eliminating manual Helm value
 * management as described in KARPENTER_EC2NODECLASS_WEBHOOK.md / NODEPOOL_CONTROLLER_MIGRATION.md.
 *
 * <p>{@code failurePolicy: Ignore} — webhook outage must not block Karpenter.
 */
@Path("/mutate-ec2nodeclass")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class Ec2NodeClassWebhookResource {

    private static final Logger LOG = Logger.getLogger(Ec2NodeClassWebhookResource.class);

    private final AdmissionController<Ec2NodeClass> controller;

    @Inject
    public Ec2NodeClassWebhookResource(ClusterIdentityService clusterIdentityService,
                                       UserDataMergeService userDataMergeService) {
        this.controller = new AdmissionController<>((resource, operation) -> {
            var identity = clusterIdentityService.get();
            if (identity == null) {
                LOG.warnf("Cluster identity unavailable — skipping userData injection for EC2NodeClass %s",
                    resource.getMetadata().getName());
                return resource;
            }

            String nodeVariant = resource.getMetadata().getAnnotations() != null
                ? resource.getMetadata().getAnnotations().get(UserDataMergeService.NODE_VARIANT_ANNOTATION)
                : null;
            String existing  = resource.getSpec() != null ? resource.getSpec().getUserData() : null;

            String merged = userDataMergeService.merge(nodeVariant, existing, identity);
            if (merged == null) {
                LOG.debugf("EC2NodeClass/%s userData already up-to-date — no-op", resource.getMetadata().getName());
                return resource;
            }

            LOG.infof("Injecting cluster bootstrap fields into EC2NodeClass/%s (node-variant=%s)",
                resource.getMetadata().getName(), nodeVariant);
            if (resource.getSpec() == null) resource.setSpec(new Ec2NodeClassSpec());
            resource.getSpec().setUserData(merged);
            return resource;
        });
    }

    @POST
    public AdmissionReview mutate(AdmissionReview review) {
        return controller.handle(review);
    }
}
