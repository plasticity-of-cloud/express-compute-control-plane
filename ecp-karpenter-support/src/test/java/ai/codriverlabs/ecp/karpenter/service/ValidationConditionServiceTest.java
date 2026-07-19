package ai.codriverlabs.ecp.karpenter.service;

import ai.codriverlabs.ecp.karpenter.model.Ec2NodeClass;
import ai.codriverlabs.ecp.karpenter.model.Ec2NodeClassStatus;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValidationConditionServiceTest {

    ValidationConditionService service;

    @BeforeEach
    void setUp() {
        service = new ValidationConditionService();
    }

    @Test
    void isValidationSucceeded_falseWhenNoStatus() {
        assertFalse(service.isValidationSucceeded(ec2NodeClass(null, 1L)));
    }

    @Test
    void isValidationSucceeded_falseWhenConditionMissing() {
        var nc = ec2NodeClass(new Ec2NodeClassStatus(), 1L);
        assertFalse(service.isValidationSucceeded(nc));
    }

    @Test
    void isValidationSucceeded_falseWhenDifferentGeneration() {
        var nc = ec2NodeClass(new Ec2NodeClassStatus(), 2L);
        service.setValidationSucceeded(nc);
        nc.getMetadata().setGeneration(3L); // bump generation
        assertFalse(service.isValidationSucceeded(nc));
    }

    @Test
    void isValidationSucceeded_trueAfterSet() {
        var nc = ec2NodeClass(null, 1L);
        service.setValidationSucceeded(nc);
        assertTrue(service.isValidationSucceeded(nc));
    }

    @Test
    void setValidationSucceeded_createsStatusWhenNull() {
        var nc = ec2NodeClass(null, 1L);
        service.setValidationSucceeded(nc);
        assertNotNull(nc.getStatus());
        assertFalse(nc.getStatus().getConditions().isEmpty());
    }

    @Test
    void setValidationSucceeded_idempotent() {
        var nc = ec2NodeClass(null, 1L);
        service.setValidationSucceeded(nc);
        service.setValidationSucceeded(nc);
        long count = nc.getStatus().getConditions().stream()
            .filter(c -> ValidationConditionService.CONDITION_TYPE.equals(c.get("type")))
            .count();
        assertEquals(1, count);
    }

    @Test
    void setValidationSucceeded_setsCorrectFields() {
        var nc = ec2NodeClass(null, 5L);
        service.setValidationSucceeded(nc);
        Map<String, Object> cond = nc.getStatus().getConditions().get(0);
        assertEquals("ValidationSucceeded", cond.get("type"));
        assertEquals("True", cond.get("status"));
        assertEquals(5L, cond.get("observedGeneration"));
        assertNotNull(cond.get("lastTransitionTime"));
    }

    @Test
    void setValidationSucceeded_replacesExistingCondition() {
        var nc = ec2NodeClass(null, 1L);
        // Add a stale False condition
        var status = new Ec2NodeClassStatus();
        status.setConditions(new ArrayList<>(List.of(
            Map.of("type", "ValidationSucceeded", "status", "False", "observedGeneration", 0L)
        )));
        nc.setStatus(status);

        service.setValidationSucceeded(nc);

        long count = nc.getStatus().getConditions().stream()
            .filter(c -> "ValidationSucceeded".equals(c.get("type")))
            .count();
        assertEquals(1, count);
        assertEquals("True", nc.getStatus().getConditions().get(0).get("status"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Ec2NodeClass ec2NodeClass(Ec2NodeClassStatus status, long generation) {
        var nc = new Ec2NodeClass();
        var meta = new ObjectMeta();
        meta.setName("default");
        meta.setGeneration(generation);
        nc.setMetadata(meta);
        nc.setStatus(status);
        return nc;
    }
}
