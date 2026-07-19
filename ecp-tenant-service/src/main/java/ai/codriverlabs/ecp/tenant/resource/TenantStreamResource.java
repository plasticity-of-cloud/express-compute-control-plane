package ai.codriverlabs.ecp.tenant.resource;

import ai.codriverlabs.ecp.tenant.TenantNaming;
import ai.codriverlabs.ecp.tenant.model.TenantProgress;
import ai.codriverlabs.ecp.tenant.service.DryRunProvisioningService;
import ai.codriverlabs.ecp.tenant.service.TenantProvisioningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE progress stream — served via Lambda Function URL (RESPONSE_STREAM mode).
 *
 * Two-phase streaming:
 *   Phase 1 — EC2 boot: polls EC2 DescribeInstances every 5s until instance is running + has public IP.
 *   Phase 2 — SQS: polls per-tenant FIFO queue for boot script progress updates.
 *             Validates state transitions before persisting. Deletes queue on terminal state.
 *
 * Auth: AWS_IAM on the Function URL.
 */
@Path("/tenants")
@Produces(MediaType.APPLICATION_JSON)
public class TenantStreamResource {

    private static final Logger LOG = Logger.getLogger(TenantStreamResource.class);

    @Inject TenantProvisioningService provisioningService;
    @Inject DryRunProvisioningService dryRunService;
    @Inject SqsClient sqs;
    @Inject ObjectMapper objectMapper;

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

        // Phase 2: Poll per-tenant SQS FIFO queue for progress updates from the boot script.
        // Validates state transitions, persists valid updates to DynamoDB, and streams SSE events.
        // Deletes the queue on terminal state (ready/failed).
        Multi<TenantProgress> sqsPhase = Multi.createFrom().ticks().every(Duration.ofSeconds(5))
            .select().first(120) // max 10 minutes
            .flatMap(tick -> {
                if (emittedTerminal.get()) return Multi.createFrom().empty();
                return Multi.createFrom().iterable(pollProgressQueue(id, emittedTerminal));
            })
            .select().first(p -> {
                if (emittedTerminal.get() && isTerminal(p)) return true; // emit the terminal event
                return !emittedTerminal.get() || !isTerminal(p);
            });

        return Multi.createBy().concatenating().streams(ec2Phase, sqsPhase);
    }

    /**
     * Polls the per-tenant progress FIFO queue, validates messages, persists valid updates,
     * and returns progress events to stream to the client.
     * On terminal state, deletes the queue immediately.
     */
    private List<TenantProgress> pollProgressQueue(String tenantId, AtomicBoolean emittedTerminal) {
        String queueUrl;
        try {
            queueUrl = sqs.getQueueUrl(GetQueueUrlRequest.builder()
                .queueName(TenantNaming.progressQueueName(tenantId)).build()).queueUrl();
        } catch (QueueDoesNotExistException e) {
            // Queue already deleted (SSE reconnect after completion) — check DynamoDB for final state
            TenantProgress fallback = provisioningService.getProgress(tenantId);
            if (fallback != null && isTerminal(fallback)) {
                emittedTerminal.set(true);
                return List.of(fallback);
            }
            return List.of(gapFill(fallback));
        }

        var messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(10)
            .waitTimeSeconds(0)
            .build()).messages();

        if (messages.isEmpty()) {
            // No messages yet — emit gap-fill so client doesn't see silence
            TenantProgress current = provisioningService.getProgress(tenantId);
            return List.of(gapFill(current));
        }

        List<TenantProgress> results = new java.util.ArrayList<>();
        for (Message msg : messages) {
            try {
                ProgressMessage pm = objectMapper.readValue(msg.body(), ProgressMessage.class);
                TenantProgress validated = validateAndPersist(tenantId, pm);
                if (validated != null) {
                    results.add(validated);
                    if (isTerminal(validated)) {
                        emittedTerminal.set(true);
                        deleteQueueQuietly(queueUrl, tenantId);
                        break; // queue is gone — don't touch it again
                    }
                }
            } catch (Exception e) {
                LOG.warnf("Failed to process progress message for tenant %s: %s", tenantId, e.getMessage());
            }
            // Always delete the message (even if invalid — prevent poison message blocking)
            sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build());
        }

        return results.isEmpty() ? List.of(gapFill(provisioningService.getProgress(tenantId))) : results;
    }

    /**
     * Validates state transition and progress direction, then persists to DynamoDB.
     */
    private TenantProgress validateAndPersist(String tenantId, ProgressMessage pm) {
        // Validate tenant identity matches
        if (!tenantId.equals(pm.tenantId())) {
            LOG.warnf("Tenant mismatch: expected %s, got %s — ignoring", tenantId, pm.tenantId());
            return null;
        }

        // Validate progress only goes forward
        TenantProgress current = provisioningService.getProgress(tenantId);
        if (current != null && pm.progress() <= current.progress() && !isTerminalState(pm.state())) {
            return null; // progress must advance (except for terminal states which always apply)
        }

        // Validate state is a known value
        if (!isValidState(pm.state())) {
            LOG.warnf("Invalid state '%s' from tenant %s — ignoring", pm.state(), tenantId);
            return null;
        }

        // Persist to DynamoDB
        provisioningService.updateProgressFromSqs(tenantId, pm.state(), pm.phase(), pm.progress());

        return new TenantProgress(pm.state(), pm.phase(), pm.progress(),
            current != null ? current.publicIp() : null, 0, null, null);
    }

    private void deleteQueueQuietly(String queueUrl, String tenantId) {
        try {
            sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
            LOG.infof("Deleted progress queue for tenant %s (provisioning complete)", tenantId);
        } catch (Exception e) {
            LOG.warnf("Failed to delete progress queue for %s: %s", tenantId, e.getMessage());
        }
    }

    private boolean isTerminal(TenantProgress p) {
        return p != null && ("ready".equals(p.state()) || "failed".equals(p.state()));
    }

    private boolean isTerminalState(String state) {
        return "ready".equals(state) || "failed".equals(state);
    }

    private boolean isValidState(String state) {
        return switch (state) {
            case "provisioning", "booting", "pulling-key",
                 "kubeadm-init", "kubeadm-done", "registering",
                 "ready", "failed" -> true;
            default -> false;
        };
    }

    /**
     * If DynamoDB still shows the initial state (progress == 0), the boot script hasn't
     * started writing to SQS yet. Emit a synthetic event so the client sees continuous progress.
     */
    private TenantProgress gapFill(TenantProgress p) {
        if (p == null || (p.progress() == 0 && "provisioning".equals(p.state()))) {
            String publicIp = p != null ? p.publicIp() : null;
            return new TenantProgress("provisioning", "Waiting for boot script...", 25,
                publicIp, 0, null, null);
        }
        return p;
    }

    /**
     * Message schema from the boot script's SQS progress reports.
     */
    public record ProgressMessage(String tenantId, String state, String phase, int progress) {}
}
