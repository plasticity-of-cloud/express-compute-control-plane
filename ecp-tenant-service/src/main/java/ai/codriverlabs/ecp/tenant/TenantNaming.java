package ai.codriverlabs.ecp.tenant;

/**
 * Naming constants for tenant-scoped AWS resources.
 * All dynamically-created resources use this prefix for IAM policy scoping and identification.
 */
public final class TenantNaming {

    public static final String RESOURCE_PREFIX = "ecp-tenant-";
    public static final String SECRET_PREFIX = "ecp/tenant/";

    private TenantNaming() {}

    public static String roleName(String tenantId) { return RESOURCE_PREFIX + tenantId + "-ir"; }
    public static String instanceProfileName(String tenantId) { return roleName(tenantId); }
    public static String dlmRoleName(String tenantId) { return RESOURCE_PREFIX + tenantId + "-dlm"; }
    public static String securityGroupName(String tenantId) { return RESOURCE_PREFIX + tenantId + "-sg"; }
    public static String keyPairName(String tenantId) { return RESOURCE_PREFIX + tenantId + "-key"; }
    public static String secretPath(String tenantId, String name) { return SECRET_PREFIX + tenantId + "/" + name; }
    public static String queueName(String clusterName) { return RESOURCE_PREFIX + clusterName; }
    public static String progressQueueName(String tenantId) { return RESOURCE_PREFIX + tenantId + "-progress.fifo"; }
    public static String eventRuleName(String clusterName, String suffix) { return RESOURCE_PREFIX + clusterName + "-" + suffix; }
}
