package ai.codriverlabs.ecp.mgmt.auth;

import ai.codriverlabs.ecp.mgmt.service.JwksTokenValidationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Validates webhook SA tokens on GET workload-identities endpoints.
 * Bearer token is optional — absent means CLI request, which passes through.
 */
@Provider
public class WebhookAuthFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(WebhookAuthFilter.class);
    private static final String WEBHOOK_SA = "system:serviceaccount:kube-system:express-compute-workload-identity-webhook";

    @Inject JwksTokenValidationService tokenValidationService;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (!path.contains("/workload-identities") || !"GET".equals(ctx.getMethod())) return;

        String authHeader = ctx.getHeaderString("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return; // CLI — let through

        String clusterName = extractClusterName(path);
        if (clusterName == null) {
            ctx.abortWith(Response.status(400)
                .entity(Map.of("__type", "InvalidParameterException", "message", "Missing cluster name"))
                .build());
            return;
        }

        try {
            tokenValidationService.validateWebhookToken(authHeader, clusterName, WEBHOOK_SA);
        } catch (Exception e) {
            LOG.warnf("Webhook auth failed: %s", e.getMessage());
            ctx.abortWith(Response.status(403)
                .entity(Map.of("__type", "AccessDeniedException", "message", "Invalid webhook token"))
                .build());
        }
    }

    private String extractClusterName(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("clusters".equals(parts[i])) return parts[i + 1];
        }
        return null;
    }
}
