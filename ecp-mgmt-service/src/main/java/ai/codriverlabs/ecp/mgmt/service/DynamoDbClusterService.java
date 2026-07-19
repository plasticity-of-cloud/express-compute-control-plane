package ai.codriverlabs.ecp.mgmt.service;

import ai.codriverlabs.ecp.model.ClusterType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DynamoDB-backed cluster registration and JWKS storage.
 * Table: ecp-clusters (PK: clusterName)
 */
@ApplicationScoped
public class DynamoDbClusterService {

    private static final Logger LOG = Logger.getLogger(DynamoDbClusterService.class);

    @Inject DynamoDbClient dynamoDb;

    @ConfigProperty(name = "express-compute.clusters-table")
    String tableName;

    public String getJwks(String clusterName) {
        Map<String, AttributeValue> item = getClusterItem(clusterName);
        if (item.containsKey("jwks")) return item.get("jwks").s();
        throw new IllegalArgumentException("Cluster not registered: " + clusterName);
    }

    public String getIssuer(String clusterName) {
        Map<String, AttributeValue> item = getClusterItem(clusterName);
        if (item.containsKey("issuer")) return item.get("issuer").s();
        throw new IllegalArgumentException("Cluster not registered: " + clusterName);
    }

    public Map<String, String> registerCluster(String clusterName, String issuer, String jwks, String ownerArn) {
        if (clusterName == null || clusterName.isBlank()) throw new IllegalArgumentException("clusterName is required");
        if (issuer == null || issuer.isBlank()) throw new IllegalArgumentException("issuer is required");
        if (jwks == null || jwks.isBlank()) throw new IllegalArgumentException("jwks is required");

        try {
            getClusterItem(clusterName);
            throw new IllegalStateException("Cluster already registered: " + clusterName);
        } catch (IllegalArgumentException e) {
            // expected — cluster does not exist yet
        }

        String now = Instant.now().toString();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("clusterName", AttributeValue.fromS(clusterName));
        item.put("issuer", AttributeValue.fromS(issuer));
        item.put("jwks", AttributeValue.fromS(jwks));
        item.put("createdAt", AttributeValue.fromS(now));
        item.put("updatedAt", AttributeValue.fromS(now));
        if (ownerArn != null) item.put("ownerArn", AttributeValue.fromS(ownerArn));

        dynamoDb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
        LOG.infof("Registered cluster: %s (owner: %s)", clusterName, ownerArn);
        return Map.of("clusterName", clusterName, "issuer", issuer, "createdAt", now);
    }

    public Map<String, String> describeCluster(String clusterName) {
        Map<String, AttributeValue> item = getClusterItem(clusterName);
        Map<String, String> result = new HashMap<>();
        result.put("clusterName", clusterName);
        if (item.containsKey("issuer")) result.put("issuer", item.get("issuer").s());
        if (item.containsKey("createdAt")) result.put("createdAt", item.get("createdAt").s());
        if (item.containsKey("updatedAt")) result.put("updatedAt", item.get("updatedAt").s());
        return result;
    }

    public List<Map<String, String>> listClusters() {
        ScanResponse response = dynamoDb.scan(ScanRequest.builder()
            .tableName(tableName)
            .projectionExpression("clusterName, issuer, createdAt")
            .build());
        return response.items().stream()
            .map(item -> {
                Map<String, String> cluster = new HashMap<>();
                if (item.containsKey("clusterName")) cluster.put("clusterName", item.get("clusterName").s());
                if (item.containsKey("issuer")) cluster.put("issuer", item.get("issuer").s());
                if (item.containsKey("createdAt")) cluster.put("createdAt", item.get("createdAt").s());
                return cluster;
            })
            .toList();
    }

    public void updateJwks(String clusterName, String jwks) {
        if (jwks == null || jwks.isBlank()) throw new IllegalArgumentException("jwks is required");
        getClusterItem(clusterName); // verify exists
        dynamoDb.updateItem(UpdateItemRequest.builder()
            .tableName(tableName)
            .key(Map.of("clusterName", AttributeValue.fromS(clusterName)))
            .updateExpression("SET jwks = :j, updatedAt = :u")
            .expressionAttributeValues(Map.of(
                ":j", AttributeValue.fromS(jwks),
                ":u", AttributeValue.fromS(Instant.now().toString())))
            .build());
        LOG.infof("Updated JWKS for cluster: %s", clusterName);
    }

    public void deregisterCluster(String clusterName) {
        getClusterItem(clusterName); // verify exists
        dynamoDb.deleteItem(DeleteItemRequest.builder()
            .tableName(tableName)
            .key(Map.of("clusterName", AttributeValue.fromS(clusterName)))
            .build());
        LOG.infof("Deregistered cluster: %s", clusterName);
    }

    public String getOwnerArn(String clusterName) {
        Map<String, AttributeValue> item = getClusterItem(clusterName);
        return item.containsKey("ownerArn") ? item.get("ownerArn").s() : null;
    }

    public List<Map<String, String>> listClustersByOwner(String ownerArn) {
        ScanResponse response = dynamoDb.scan(ScanRequest.builder()
            .tableName(tableName)
            .filterExpression("ownerArn = :owner")
            .expressionAttributeValues(Map.of(":owner", AttributeValue.fromS(ownerArn)))
            .projectionExpression("clusterName, issuer, createdAt, ownerArn")
            .build());
        return response.items().stream().map(this::itemToMap).toList();
    }

    private Map<String, String> itemToMap(Map<String, AttributeValue> item) {
        Map<String, String> result = new HashMap<>();
        for (String key : List.of("clusterName", "issuer", "createdAt", "ownerArn")) {
            if (item.containsKey(key)) result.put(key, item.get(key).s());
        }
        return result;
    }

    /**
     * Get the cluster type for routing to the correct SPI provider.
     * Defaults to MANAGED for clusters registered before type was added (backward compatible).
     */
    public ClusterType getClusterType(String clusterName) {
        Map<String, AttributeValue> item = getClusterItem(clusterName);
        if (item.containsKey("clusterType")) {
            return ClusterType.fromString(item.get("clusterType").s());
        }
        return ClusterType.MANAGED; // backward compatible default
    }

    private Map<String, AttributeValue> getClusterItem(String clusterName) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(tableName)
            .key(Map.of("clusterName", AttributeValue.fromS(clusterName)))
            .build());
        if (!response.hasItem() || response.item().isEmpty())
            throw new IllegalArgumentException("Cluster not registered: " + clusterName);
        return response.item();
    }
}
