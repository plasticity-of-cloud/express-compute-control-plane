package ai.codriverlabs.eksdx.model;

import java.util.Map;

public record TokenClaims(
    String namespace,
    String serviceAccount,
    String serviceAccountUid,
    String podName,
    String podUid,
    String subject
) {
    public Map<String, String> sessionTags() {
        return Map.of(
            "kubernetes-namespace", namespace,
            "kubernetes-service-account", serviceAccount,
            "kubernetes-pod-name", podName != null ? podName : "",
            "kubernetes-pod-uid", podUid != null ? podUid : ""
        );
    }
}
