package ai.codriverlabs.ecp.resource;

import ai.codriverlabs.ecp.service.CredentialServiceClient;
import ai.codriverlabs.ecp.service.TokenValidationService;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * AWS-API-compatible endpoint that the EKS Workload Identity Agent calls.
 * Validates the token locally via TokenReview (fast-fail), then forwards
 * the full credential exchange to the Express Compute Lambda service.
 */
@Path("/clusters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthProxyResource {

    private static final Logger LOG = Logger.getLogger(AuthProxyResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject TokenValidationService tokenValidationService;
    @Inject CredentialServiceClient forwardingService;

    public static class AgentRequest {
        @JsonProperty("token") public String token;
    }

    @POST
    @Path("/{clusterName}/assets")
    public Response assumeRoleForPodIdentityAssets(
            @PathParam("clusterName") String clusterName,
            AgentRequest request) {
        return assumeRoleForPodIdentity(clusterName, request);
    }

    @POST
    @Path("/{clusterName}/assume-role-for-pod-identity")
    public Response assumeRoleForPodIdentity(
            @PathParam("clusterName") String clusterName,
            AgentRequest request) {
        try {
            if (request == null || request.token == null || request.token.isEmpty()) {
                return error(400, "InvalidParameterException", "token is required");
            }

            LOG.infof("Agent API: AssumeRoleForPodIdentity cluster=%s", clusterName);

            // 1. Fast-fail: validate token via Kubernetes TokenReview
            tokenValidationService.validateToken(request.token, clusterName);

            // 2. Forward to Lambda for full credential exchange
            String body = MAPPER.writeValueAsString(Map.of("token", request.token));
            CredentialServiceClient.ForwardResult result =
                forwardingService.forward(clusterName, body);

            return Response.status(result.statusCode())
                .entity(result.body())
                .type(MediaType.APPLICATION_JSON)
                .build();

        } catch (SecurityException e) {
            return error(403, "AccessDeniedException", e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(400, "InvalidParameterException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Agent API error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    private Response error(int status, String code, String message) {
        return Response.status(status)
                .entity(Map.of("__type", code, "message", message))
                .build();
    }
}
