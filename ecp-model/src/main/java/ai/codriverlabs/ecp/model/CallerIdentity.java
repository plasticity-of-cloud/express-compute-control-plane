package ai.codriverlabs.ecp.model;

/**
 * Extracts and normalizes the caller identity from API Gateway request context.
 * Works with both quarkus-amazon-lambda-rest (mgmt-service) and
 * Lambda Web Adapter (tenant-service, via x-amzn-request-context header).
 */
public final class CallerIdentity {

    private CallerIdentity() {}

    /**
     * Normalizes a caller ARN to a stable principal (strips session name for assumed roles).
     * <pre>
     * "arn:aws:sts::123:assumed-role/RoleName/session" → "arn:aws:iam::123:role/RoleName"
     * "arn:aws:iam::123:user/john"                     → "arn:aws:iam::123:user/john" (unchanged)
     * </pre>
     */
    public static String normalize(String userArn) {
        if (userArn == null || userArn.isBlank()) return null;
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

    /**
     * Extracts userArn from a JSON request context string (x-amzn-request-context header).
     * Lightweight extraction without a full JSON parser dependency.
     */
    public static String extractUserArnFromContext(String requestContextJson) {
        if (requestContextJson == null) return null;
        // Look for "userArn":"..." in the identity block
        int idx = requestContextJson.indexOf("\"userArn\"");
        if (idx < 0) return null;
        int colonIdx = requestContextJson.indexOf(":", idx + 9);
        if (colonIdx < 0) return null;
        int startQuote = requestContextJson.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return null;
        int endQuote = requestContextJson.indexOf("\"", startQuote + 1);
        if (endQuote < 0) return null;
        return requestContextJson.substring(startQuote + 1, endQuote);
    }

    /**
     * Extracts sourceIp from a JSON request context string.
     */
    public static String extractSourceIpFromContext(String requestContextJson) {
        if (requestContextJson == null) return null;
        int idx = requestContextJson.indexOf("\"sourceIp\"");
        if (idx < 0) return null;
        int colonIdx = requestContextJson.indexOf(":", idx + 10);
        if (colonIdx < 0) return null;
        int startQuote = requestContextJson.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return null;
        int endQuote = requestContextJson.indexOf("\"", startQuote + 1);
        if (endQuote < 0) return null;
        return requestContextJson.substring(startQuote + 1, endQuote);
    }
}
