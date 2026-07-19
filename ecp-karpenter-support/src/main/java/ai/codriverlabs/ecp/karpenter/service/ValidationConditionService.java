package ai.codriverlabs.ecp.karpenter.service;

import ai.codriverlabs.ecp.karpenter.model.Ec2NodeClass;
import ai.codriverlabs.ecp.karpenter.model.Ec2NodeClassStatus;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the {@code ValidationSucceeded} status condition on EC2NodeClass resources.
 *
 * <p>Replaces the fragile {@code kubectl proxy + curl PATCH} block in configure-nodepools.sh.
 * The condition is set on the in-memory resource object; the reconciler persists it via
 * {@code UpdateControl.patchStatus()}.
 */
@ApplicationScoped
public class ValidationConditionService {

    private static final Logger LOG = Logger.getLogger(ValidationConditionService.class);
    static final String CONDITION_TYPE = "ValidationSucceeded";

    /**
     * Returns true if {@code ValidationSucceeded=True} is already set for the current
     * generation, making the reconcile loop a no-op.
     */
    public boolean isValidationSucceeded(Ec2NodeClass resource) {
        if (resource.getStatus() == null || resource.getStatus().getConditions() == null) return false;
        long gen = generation(resource);
        return resource.getStatus().getConditions().stream()
            .anyMatch(c -> CONDITION_TYPE.equals(c.get("type"))
                && "True".equals(c.get("status"))
                && gen == toLong(c.get("observedGeneration")));
    }

    /**
     * Upserts the {@code ValidationSucceeded=True} condition on the resource's status.
     * The caller must then call {@code UpdateControl.patchStatus(resource)}.
     */
    public void setValidationSucceeded(Ec2NodeClass resource) {
        if (resource.getStatus() == null) resource.setStatus(new Ec2NodeClassStatus());
        List<Map<String, Object>> conditions = resource.getStatus().getConditions();
        if (conditions == null) { conditions = new ArrayList<>(); resource.getStatus().setConditions(conditions); }

        conditions.removeIf(c -> CONDITION_TYPE.equals(c.get("type")));

        Map<String, Object> cond = new LinkedHashMap<>();
        cond.put("type", CONDITION_TYPE);
        cond.put("status", "True");
        cond.put("reason", "ValidationSucceeded");
        cond.put("message", "EC2NodeClass validated by ecp-karpenter-support");
        cond.put("lastTransitionTime", Instant.now().toString());
        cond.put("observedGeneration", generation(resource));
        conditions.add(cond);

        LOG.infof("Set ValidationSucceeded=True on EC2NodeClass %s (gen=%d)",
            resource.getMetadata().getName(), generation(resource));
    }

    private long generation(Ec2NodeClass r) {
        Long g = r.getMetadata().getGeneration();
        return g != null ? g : 0L;
    }

    private long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) { try { return Long.parseLong(s); } catch (NumberFormatException ignored) {} }
        return -1L;
    }
}
