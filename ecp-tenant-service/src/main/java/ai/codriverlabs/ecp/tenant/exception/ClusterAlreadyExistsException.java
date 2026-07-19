package ai.codriverlabs.ecp.tenant.exception;

/**
 * Thrown when a cluster creation is attempted for a name that already exists in DynamoDB.
 * Maps to HTTP 409 ResourceInUseException at the API layer.
 *
 * <p>The message is intentionally user-facing — it is forwarded directly to the CLI caller.
 */
public class ClusterAlreadyExistsException extends RuntimeException {

    private final String clusterName;

    public ClusterAlreadyExistsException(String clusterName) {
        super(String.format(
            "Cluster '%s' already exists. To replace it, run: ecp delete-cluster %s",
            clusterName, clusterName));
        this.clusterName = clusterName;
    }

    public String getClusterName() {
        return clusterName;
    }
}
