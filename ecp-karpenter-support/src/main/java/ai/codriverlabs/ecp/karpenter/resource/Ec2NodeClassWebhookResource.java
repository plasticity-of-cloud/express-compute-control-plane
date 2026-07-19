package ai.codriverlabs.ecp.karpenter.resource;

import ai.codriverlabs.ecp.karpenter.model.Ec2NodeClass;
import ai.codriverlabs.ecp.karpenter.model.Ec2NodeClassSpec;
import ai.codriverlabs.ecp.karpenter.service.AmiAliasResolverService;
import ai.codriverlabs.ecp.karpenter.service.ClusterIdentityService;
import ai.codriverlabs.ecp.karpenter.service.NodePoolArchService;
import ai.codriverlabs.ecp.karpenter.service.UserDataMergeService;
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
import java.util.List;
import java.util.Map;

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
 *       {@code Platform=express-compute}, {@code Tenant=<tenantId>}, {@code ManagedBy=Karpenter}.
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
                                       UserDataMergeService userDataMergeService,
                                       AmiAliasResolverService amiAliasResolverService,
                                       NodePoolArchService nodePoolArchService) {
        this.controller = new AdmissionController<>((resource, operation) -> {
            var identity = clusterIdentityService.get();
            if (identity == null) {
                LOG.warnf("Cluster identity unavailable — skipping mutation for EC2NodeClass %s",
                    resource.getMetadata().getName());
                return resource;
            }

            if (resource.getSpec() == null) resource.setSpec(new Ec2NodeClassSpec());
            String originalAmiFamily = resource.getSpec().getAmiFamily();

            // 1. Resolve AMI aliases in amiSelectorTerms → concrete AMI IDs for relevant arches
            var amiTerms = resource.getSpec().getAmiSelectorTerms();
            if (amiTerms != null) {
                List<String> arches = nodePoolArchService.archesFor(resource.getMetadata().getName());
                var resolved = new java.util.ArrayList<Map<String, Object>>();
                for (var term : amiTerms) {
                    Object aliasVal = term.get("alias");
                    if (aliasVal != null) {
                        for (String arch : arches) {
                            String amiId = amiAliasResolverService.resolve(aliasVal.toString(), arch);
                            if (amiId != null) resolved.add(Map.of("id", amiId));
                        }
                        if (resolved.isEmpty()) resolved.add(term); // keep original if all resolutions failed
                        LOG.infof("EC2NodeClass/%s: resolved alias '%s' (arches=%s) → %d AMI(s)",
                            resource.getMetadata().getName(), aliasVal, arches, resolved.size());
                    } else {
                        resolved.add(term);
                    }
                }
                resource.getSpec().setAmiSelectorTerms(resolved);
            }

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

            // 3. Set instanceProfile from tenant naming convention (no discovery needed)
            if (resource.getSpec().getInstanceProfile() == null || resource.getSpec().getInstanceProfile().isBlank()) {
                resource.getSpec().setInstanceProfile(
                    "express-compute-tenant-" + identity.tenantId() + "-instance-role");
            }

            // 3. Set associatePublicIPAddress based on NAT gateway availability
            // nat=true → private egress via NAT, no public IP needed → false
            // nat=false → direct internet access required → true
            if (resource.getSpec().getAssociatePublicIPAddress() == null) {
                resource.getSpec().setAssociatePublicIPAddress(!identity.natGatewayEnabled());
            }

            // 4. Always enforce subnetSelectorTerms from cluster identity (tenant cannot override)
            if (identity.karpenterSubnetId() != null) {
                resource.getSpec().setSubnetSelectorTerms(
                    List.of(Map.of("id", identity.karpenterSubnetId())));
            }

            // 5. Always enforce securityGroupSelectorTerms from cluster identity (tenant cannot override)
            if (identity.securityGroupId() != null) {
                resource.getSpec().setSecurityGroupSelectorTerms(
                    List.of(Map.of("id", identity.securityGroupId())));
            }

            // 6. Ensure required tags — merge over customer tags, do not overwrite
            var tags = resource.getSpec().getTags();
            if (tags == null) { tags = new HashMap<>(); resource.getSpec().setTags(tags); }
            tags.putIfAbsent("Platform", "express-compute");
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
