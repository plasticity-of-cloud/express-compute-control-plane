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

import java.util.HashMap;

/**
 * Mutating admission webhook for Karpenter EC2NodeClass (CREATE and UPDATE).
 *
 * <p>On admission:
 * <ol>
 *   <li>Reads {@code spec.amiFamily} to determine userData format (Bottlerocket → TOML, AL2023 → MIME).
 *   <li>Merges cluster bootstrap fields into {@code spec.userData}.
 *   <li>Rewrites {@code spec.amiFamily} to {@code "Custom"} — prevents Karpenter ≥1.10 from
 *       calling {@code eks:DescribeCluster} when {@code eksControlPlane=false}.
 *   <li>Ensures required tags are present in {@code spec.tags}, preserving customer-supplied tags:
 *       {@code Platform=eks-d-xpress}, {@code Tenant=<tenantId>}, {@code ManagedBy=Karpenter}.
 * </ol>
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
                LOG.warnf("Cluster identity unavailable — skipping mutation for EC2NodeClass %s",
                    resource.getMetadata().getName());
                return resource;
            }

            if (resource.getSpec() == null) resource.setSpec(new Ec2NodeClassSpec());
            String originalAmiFamily = resource.getSpec().getAmiFamily();

            // 1. Merge userData based on customer-supplied amiFamily
            String merged = userDataMergeService.merge(originalAmiFamily, resource.getSpec().getUserData(), identity);
            if (merged != null) {
                resource.getSpec().setUserData(merged);
                LOG.infof("Injected cluster bootstrap fields into EC2NodeClass/%s (amiFamily=%s)",
                    resource.getMetadata().getName(), originalAmiFamily);
            }

            // 2. Rewrite amiFamily to Custom — prevents Karpenter >=1.10 EKS API calls
            if (!UserDataMergeService.CUSTOM.equals(originalAmiFamily)) {
                resource.getSpec().setAmiFamily(UserDataMergeService.CUSTOM);
                LOG.infof("Rewrote EC2NodeClass/%s amiFamily: %s → Custom",
                    resource.getMetadata().getName(), originalAmiFamily);
            }

            // 3. Ensure required tags — merge over customer tags, do not overwrite
            var tags = resource.getSpec().getTags();
            if (tags == null) { tags = new HashMap<>(); resource.getSpec().setTags(tags); }
            tags.putIfAbsent("Platform", "eks-d-xpress");
            tags.putIfAbsent("Tenant", identity.tenantId());
            tags.putIfAbsent("ManagedBy", "Karpenter");

            return resource;
        });
    }

    @POST
    public AdmissionReview mutate(AdmissionReview review) {
        return controller.handle(review);
    }
}
