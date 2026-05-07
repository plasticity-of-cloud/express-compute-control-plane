package cloud.plasticity.eksdx.lambda.auth;

import cloud.plasticity.eksdx.lambda.service.JwksTokenValidationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Validates webhook SA tokens on management API endpoints.
 * The /assets endpoint is NOT filtered here — the token is in the request body
 * and validated by EksAuthResource directly.
 */
@Provider
public class WebhookAuthFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(WebhookAuthFilter.class);
    private static final String WEBHOOK_SA = "system:serviceaccount:kube-system:eks-dx-pod-identity-webhook";

    @Inject
    JwksTokenValidationService tokenValidationService;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();

        // Only filter webhook queries — GET on pod-identity-associations with Bearer token
        if (!path.contains("/pod-identity-associations") || !"GET".equals(ctx.getMethod())) {
            return;
        }

        String authHeader = ctx.getHeaderString("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // No Bearer token — could be CLI request, let it through
            // (CLI auth is handled separately)
            return;
        }

        // Extract cluster name from path: /clusters/{name}/pod-identity-associations
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
        // path: clusters/{name}/pod-identity-associations
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("clusters".equals(parts[i])) {
                return parts[i + 1];
            }
        }
        return null;
    }
}
