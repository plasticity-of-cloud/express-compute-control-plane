package ai.codriverlabs.eksdx.tenant.auth;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Extracts caller identity from Lambda Web Adapter's x-amzn-request-context header.
 * Sets "callerArn" and "sourceIp" as request properties for downstream use.
 */
@Provider
public class CallerIdentityFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(CallerIdentityFilter.class);

    @Override
    public void filter(ContainerRequestContext ctx) {
        String requestContext = ctx.getHeaderString("x-amzn-request-context");
        if (requestContext == null) return;

        String userArn = extractField(requestContext, "userArn");
        if (userArn != null) {
            ctx.setProperty("callerArn", normalize(userArn));
        }

        String sourceIp = extractField(requestContext, "sourceIp");
        if (sourceIp != null) {
            ctx.setProperty("sourceIp", sourceIp);
        }

        LOG.debugf("Caller: %s (source: %s)", userArn, sourceIp);
    }

    /**
     * Normalizes assumed-role ARN to stable IAM role ARN.
     * "arn:aws:sts::123:assumed-role/RoleName/session" → "arn:aws:iam::123:role/RoleName"
     */
    static String normalize(String userArn) {
        if (userArn == null) return null;
        if (userArn.contains(":assumed-role/")) {
            String afterAssumedRole = userArn.split(":assumed-role/")[1];
            String roleName = afterAssumedRole.contains("/")
                ? afterAssumedRole.substring(0, afterAssumedRole.indexOf("/"))
                : afterAssumedRole;
            String account = userArn.split(":")[4];
            return "arn:aws:iam::" + account + ":role/" + roleName;
        }
        return userArn;
    }

    private static String extractField(String json, String fieldName) {
        int idx = json.indexOf("\"" + fieldName + "\"");
        if (idx < 0) return null;
        int colonIdx = json.indexOf(":", idx + fieldName.length() + 2);
        if (colonIdx < 0) return null;
        int startQuote = json.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return null;
        int endQuote = json.indexOf("\"", startQuote + 1);
        if (endQuote < 0) return null;
        return json.substring(startQuote + 1, endQuote);
    }
}
