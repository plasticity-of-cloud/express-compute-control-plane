package ai.codriverlabs.eksdx.tenant.model;

/**
 * SSE event payload for GET /tenants/{id}/stream.
 */
public record TenantProgress(
    String state,
    String phase,
    int progress,
    String publicIp,
    long elapsed,
    String error
) {}
