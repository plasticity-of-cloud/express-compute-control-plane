package ai.codriverlabs.ecp.model;

public record TokenClaims(
    String namespace,
    String serviceAccount,
    String serviceAccountUid,
    String podName,
    String podUid,
    String subject
) {
}
