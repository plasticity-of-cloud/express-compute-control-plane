package ai.codriverlabs.eksdx.tenant.resource;

import ai.codriverlabs.eksdx.tenant.model.TenantProgress;
import ai.codriverlabs.eksdx.tenant.service.DryRunProvisioningService;
import ai.codriverlabs.eksdx.tenant.service.TenantProvisioningService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE progress stream — served via Lambda Function URL (RESPONSE_STREAM mode).
 *
 * Two-phase streaming:
 *   Phase 1 — EC2 boot: polls EC2 DescribeInstances every 5s until instance is running + has public IP.
 *   Phase 2 — DynamoDB: polls DynamoDB every 5s for boot script progress updates.
 *             Emits events until state == "ready" or "failed".
 *
 * Auth: AWS_IAM on the Function URL.
 */
@Path("/tenants")
@Produces(MediaType.APPLICATION_JSON)
public class TenantStreamResource {

    @Inject TenantProvisioningService provisioningService;
    @Inject DryRunProvisioningService dryRunService;

    @GET
    @Path("/{id}/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Blocking
    public Multi<TenantProgress> streamProgress(@PathParam("id") String id) {
        if (dryRunService.isEnabled()) {
            return streamDryRun();
        }
        return streamReal(id);
    }

    private Multi<TenantProgress> streamDryRun() {
        var events = dryRunService.getSimulatedEvents();
        AtomicInteger idx = new AtomicInteger(0);
        return Multi.createFrom().ticks().every(Duration.ofSeconds(2))
            .select().first(events.size())
            .map(tick -> events.get(idx.getAndIncrement()));
    }

    private Multi<TenantProgress> streamReal(String id) {
        AtomicBoolean emittedTerminal = new AtomicBoolean(false);

        // Phase 1: EC2 boot — tick-based polling, emits events incrementally.
        // Terminates (and emits the final event) when phase == "provisioning_started".
        AtomicBoolean ec2Done = new AtomicBoolean(false);
        Multi<TenantProgress> ec2Phase = Multi.createFrom().ticks().every(Duration.ofSeconds(5))
            .select().first(36) // max 3 minutes
            .flatMap(tick -> {
                if (ec2Done.get()) return Multi.createFrom().empty();
                TenantProgress p = provisioningService.pollEc2BootTick(id);
                if (p == null) return Multi.createFrom().empty();
                if ("provisioning_started".equals(p.phase())) ec2Done.set(true);
                return Multi.createFrom().item(p);
            })
            .select().first(36);

        // Phase 2: poll DynamoDB every 5s. Fill gap with synthetic "Instance booting..."
        // events until the boot script writes its first progress update.
        Multi<TenantProgress> dynamoPhase = Multi.createBy().concatenating().streams(
            Multi.createFrom().item(() -> gapFill(provisioningService.getProgress(id))),
            Multi.createFrom().ticks().every(Duration.ofSeconds(5))
                .select().first(96)
                .map(tick -> gapFill(provisioningService.getProgress(id)))
        ).select().first(p -> {
            if (emittedTerminal.get()) return false;
            boolean terminal = "ready".equals(p.state()) || "failed".equals(p.state());
            if (terminal) emittedTerminal.set(true);
            return true;
        });

        return Multi.createBy().concatenating().streams(ec2Phase, dynamoPhase);
    }

    /**
     * If DynamoDB still shows the initial "EC2 instance launched" state (progress == 0),
     * the boot script hasn't started writing yet. Emit a synthetic event so the client
     * sees continuous progress instead of silence.
     */
    private TenantProgress gapFill(TenantProgress p) {
        if (p.progress() == 0 && "provisioning".equals(p.state())) {
            return new TenantProgress("provisioning", "Instance booting...", 25,
                p.publicIp(), p.elapsed(), null, null);
        }
        return p;
    }
}
