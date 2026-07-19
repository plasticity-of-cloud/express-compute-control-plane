package ai.codriverlabs.ecp.tenant.auth;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Extracts caller identity from Lambda Web Adapter's x-amzn-request-context header.
 * Sets "callerArn", "idcUserId", and "sourceIp" as request properties for downstream use.
 *
 * For IAM Identity Center users, extracts the IDC user UUID from the
 * sts:identity-context (principalId) or falls back to the session name in the
 * assumed-role ARN.
 */
@Provider
public class CallerIdentityFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(CallerIdentityFilter.class);
    private static final java.util.regex.Pattern IDC_USER_ID_PATTERN =
        java.util.regex.Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Override
    public void filter(ContainerRequestContext ctx) {
        String requestContext = ctx.getHeaderString("x-amzn-request-context");
        if (requestContext == null) return;

        String userArn = extractField(requestContext, "userArn");
        if (userArn != null) {
            ctx.setProperty("callerArn", normalize(userArn));
        }

        // Extract IDC user ID: principalId field contains "AROAXXXX:<session-name>"
        // For IDC users, session name is typically the email or IDC user UUID
        String principalId = extractField(requestContext, "principalId");
        String idcUserId = extractIdcUserId(principalId, userArn);
        if (idcUserId != null) {
            ctx.setProperty("idcUserId", idcUserId);
        }

        String sourceIp = extractField(requestContext, "sourceIp");
        if (sourceIp != null) {
            ctx.setProperty("sourceIp", sourceIp);
        }

        LOG.debugf("Caller: %s (source: %s, idcUserId: %s)", userArn, sourceIp, idcUserId);
    }

    /**
     * Extracts the IAM Identity Center user ID from the principal context.
     *
     * Resolution order:
     * 1. If principalId contains a UUID-format session name → use it (IDC passes user UUID as session)
     * 2. If assumed-role ARN has a session name that looks like an email → use as stable identity
     * 3. Fallback to the full session name (whatever it is)
     */
    static String extractIdcUserId(String principalId, String userArn) {
        // Try session name from principalId ("AROAXXXX:session-name")
        String sessionName = null;
        if (principalId != null && principalId.contains(":")) {
            sessionName = principalId.substring(principalId.indexOf(":") + 1);
        }
        // If session name is a UUID, it's likely the IDC user ID
        if (sessionName != null && IDC_USER_ID_PATTERN.matcher(sessionName).matches()) {
            return sessionName;
        }
        // Fallback: extract session name from assumed-role ARN
        if (userArn != null && userArn.contains(":assumed-role/")) {
            String afterRole = userArn.split(":assumed-role/")[1];
            if (afterRole.contains("/")) {
                return afterRole.substring(afterRole.lastIndexOf("/") + 1);
            }
        }
        return sessionName;
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
