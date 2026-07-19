package ai.codriverlabs.ecp.tenant.model;

/**
 * SSE event payload for GET /tenants/{id}/stream.
 * sshPrivateKey is populated only when state == "ready".
 */
public record TenantProgress(
    String state,
    String phase,
    int progress,
    String publicIp,
    long elapsed,
    String error,
    String sshPrivateKey
) {}
