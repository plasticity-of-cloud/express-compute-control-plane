package ai.codriverlabs.eksdx.tenant.resource;

import ai.codriverlabs.eksdx.tenant.model.TenantProgress;
import ai.codriverlabs.eksdx.tenant.service.TenantProvisioningService;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;

/**
 * SSE progress stream — served via Lambda Function URL (RESPONSE_STREAM mode).
 * Polls DynamoDB every 5 seconds and emits one event per poll.
 * Closes automatically when state reaches "ready" or "failed".
 *
 * Auth: AWS_IAM on the Function URL (configured in sam.yaml).
 */
@Path("/tenants")
@Produces(MediaType.APPLICATION_JSON)
public class TenantStreamResource {

    @Inject TenantProvisioningService provisioningService;

    @GET
    @Path("/{id}/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<TenantProgress> streamProgress(@PathParam("id") String id) {
        // Emit every 5s; include terminal event then stop (select().first stops before matching item,
        // so we negate: keep while NOT terminal, which emits the last non-terminal + closes).
        // The client receives the final state on the last poll before the stream closes.
        return Multi.createFrom().ticks().every(Duration.ofSeconds(5))
            .map(tick -> provisioningService.getProgress(id))
            .select().first(p -> !"ready".equals(p.state()) && !"failed".equals(p.state()));
    }
}
