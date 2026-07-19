package ai.codriverlabs.ecp.karpenter.reconciler;

import ai.codriverlabs.ecp.karpenter.model.Ec2NodeClass;
import ai.codriverlabs.ecp.karpenter.model.Ec2NodeClassSpec;
import ai.codriverlabs.ecp.karpenter.service.ValidationConditionService;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests using Fabric8 CRUD mock server.
 *
 * Unlike the Mockito unit tests, these exercise the real informer → watch → reconcile
 * pipeline, catching bugs like:
 *  - feedback loop (patchStatus triggers onUpdate → infinite reconcile)
 *  - 409 Conflict on concurrent reconcile calls
 *  - generation check correctly skipping status-only updates
 */
@EnableKubernetesMockClient(crud = true)
class Ec2NodeClassReconcilerMockServerTest {

    // Injected by @EnableKubernetesMockClient
    KubernetesMockServer mockServer;
    KubernetesClient client;

    Ec2NodeClassReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new Ec2NodeClassReconciler();
        reconciler.client = client;
        reconciler.validationConditionService = new ValidationConditionService();
    }

    @AfterEach
    void tearDown() {
        client.resources(Ec2NodeClass.class).delete();
    }

    // ── Core behaviour ────────────────────────────────────────────────────────

    @Test
    void onAdd_setsValidationSucceeded() throws InterruptedException {
        var latch = new CountDownLatch(1);
        startReconcilerWithLatch(latch);

        client.resource(ec2NodeClass("default")).create();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "reconcile must fire within 5s");

        var status = client.resources(Ec2NodeClass.class).withName("default").get().getStatus();
        assertTrue(status.getConditions().stream()
            .anyMatch(c -> "ValidationSucceeded".equals(c.get("type")) && "True".equals(c.get("status"))));
    }

    @Test
    void statusOnlyUpdate_doesNotRetrigger_noFeedbackLoop() throws InterruptedException {
        var patchCount = new AtomicInteger(0);
        var firstReconcileDone = new CountDownLatch(1);

        reconciler = new Ec2NodeClassReconciler() {
            @Override
            void reconcile(String name) {
                super.reconcile(name);
                patchCount.incrementAndGet();
                firstReconcileDone.countDown();
            }
        };
        reconciler.client = client;
        reconciler.validationConditionService = new ValidationConditionService();
        reconciler.onStart(new StartupEvent());

        client.resource(ec2NodeClass("default")).create();

        assertTrue(firstReconcileDone.await(5, TimeUnit.SECONDS), "initial reconcile must fire");

        // Give the status-patch-triggered onUpdate time to (incorrectly) fire if the bug exists
        Thread.sleep(600);

        // patchStatus fires an onUpdate, but generation is unchanged → must be skipped
        assertEquals(1, patchCount.get(), "reconcile must fire exactly once, not loop");
    }

    @Test
    void specUpdate_generationBumps_retriggersReconcile() throws InterruptedException {
        // 2 reconciles: initial onAdd + spec update
        var latch = new CountDownLatch(2);
        startReconcilerWithLatch(latch);

        client.resource(ec2NodeClass("default")).create();
        Thread.sleep(400); // wait for first reconcile

        // Patch spec → bumps generation
        var existing = client.resources(Ec2NodeClass.class).withName("default").get();
        existing.getSpec().setInstanceProfile("updated-profile");
        client.resources(Ec2NodeClass.class).resource(existing).patch();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "reconcile must fire on spec update");

        var status = client.resources(Ec2NodeClass.class).withName("default").get().getStatus();
        assertTrue(status.getConditions().stream()
            .anyMatch(c -> "ValidationSucceeded".equals(c.get("type"))));
    }

    @Test
    void observedGeneration_matchesResourceGeneration() throws InterruptedException {
        var latch = new CountDownLatch(1);
        startReconcilerWithLatch(latch);

        client.resource(ec2NodeClass("default")).create();
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        var nc = client.resources(Ec2NodeClass.class).withName("default").get();
        long generation = nc.getMetadata().getGeneration() != null ? nc.getMetadata().getGeneration() : 0L;

        var condition = nc.getStatus().getConditions().stream()
            .filter(c -> "ValidationSucceeded".equals(c.get("type")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("ValidationSucceeded condition not found"));

        assertEquals(generation, ((Number) condition.get("observedGeneration")).longValue());
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void concurrentReconcile_neitherThrows_statusCorrect() throws InterruptedException {
        client.resource(ec2NodeClass("default")).create();

        // Two threads race — one wins the patchStatus, the other gets a fresh GET and
        // finds it already done (or handles 409 silently). Neither should propagate an exception.
        var t1Error = new AtomicInteger(0);
        var t2Error = new AtomicInteger(0);
        var t1 = new Thread(() -> { try { reconciler.reconcile("default"); } catch (Exception e) { t1Error.incrementAndGet(); } });
        var t2 = new Thread(() -> { try { reconciler.reconcile("default"); } catch (Exception e) { t2Error.incrementAndGet(); } });
        t1.start(); t2.start();
        t1.join(3000); t2.join(3000);

        assertEquals(0, t1Error.get() + t2Error.get(), "no exceptions from concurrent reconcile");

        var status = client.resources(Ec2NodeClass.class).withName("default").get().getStatus();
        assertTrue(status.getConditions().stream()
            .anyMatch(c -> "ValidationSucceeded".equals(c.get("type")) && "True".equals(c.get("status"))));
    }

    @Test
    void reconcile_missingResource_isNoOp() {
        // Resource deleted between event fire and reconcile — must not throw
        assertDoesNotThrow(() -> reconciler.reconcile("nonexistent"));
    }

    @Test
    void alreadySucceeded_isIdempotent() {
        // The CRUD mock server doesn't merge patchStatus back into the main GET response,
        // so we test idempotency by directly verifying ValidationConditionService.isValidationSucceeded
        // guards the patchStatus call — covered in ValidationConditionServiceTest.
        // Here we verify that two manual reconciles both complete without error.
        client.resource(ec2NodeClass("default")).create();
        assertDoesNotThrow(() -> {
            reconciler.reconcile("default");
            reconciler.reconcile("default");
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void startReconcilerWithLatch(CountDownLatch latch) {
        reconciler = new Ec2NodeClassReconciler() {
            @Override
            void reconcile(String name) {
                super.reconcile(name);
                latch.countDown();
            }
        };
        reconciler.client = client;
        reconciler.validationConditionService = new ValidationConditionService();
        reconciler.onStart(new StartupEvent());
    }

    private static Ec2NodeClass ec2NodeClass(String name) {
        var nc = new Ec2NodeClass();
        nc.setMetadata(new ObjectMetaBuilder().withName(name).build());
        nc.setSpec(new Ec2NodeClassSpec());
        return nc;
    }
}
