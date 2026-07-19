package ai.codriverlabs.ecp.mgmt.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.GetRoleRequest;
import software.amazon.awssdk.services.iam.model.GetRoleResponse;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;

import java.time.Instant;
import java.util.*;

/**
 * DynamoDB-backed association lookup and CRUD.
 * Table: ecp-associations (PK: CLUSTER#clusterName, SK: namespace#serviceAccount)
 */
@ApplicationScoped
public class DynamoDbAssociationService {

    private static final Logger LOG = Logger.getLogger(DynamoDbAssociationService.class);

    @Inject DynamoDbClient dynamoDb;
    @Inject IamClient iamClient;
    @Inject TrustPolicyService trustPolicyService;

    @ConfigProperty(name = "express-compute.associations-table")
    String tableName;

    public String getRoleArn(String clusterName, String namespace, String serviceAccount) {
        Map<String, AttributeValue> item = getAssociationItem(clusterName, namespace, serviceAccount);
        return (item != null && item.containsKey("roleArn")) ? item.get("roleArn").s() : null;
    }

    public Map<String, String> createAssociation(String clusterName, String namespace,
                                                   String serviceAccount, String roleArn) {
        if (clusterName == null || clusterName.isBlank()) throw new IllegalArgumentException("clusterName is required");
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace is required");
        if (serviceAccount == null || serviceAccount.isBlank()) throw new IllegalArgumentException("serviceAccount is required");
        if (roleArn == null || roleArn.isBlank()) throw new IllegalArgumentException("roleArn is required");

        validateRoleExists(roleArn);

        if (getRoleArn(clusterName, namespace, serviceAccount) != null)
            throw new IllegalStateException("Association already exists for " +
                namespace + "/" + serviceAccount + " in cluster " + clusterName);

        String associationId = "assoc-" + UUID.randomUUID().toString().substring(0, 12);
        String now = Instant.now().toString();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS("CLUSTER#" + clusterName));
        item.put("SK", AttributeValue.fromS(namespace + "#" + serviceAccount));
        item.put("associationId", AttributeValue.fromS(associationId));
        item.put("clusterName", AttributeValue.fromS(clusterName));
        item.put("namespace", AttributeValue.fromS(namespace));
        item.put("serviceAccount", AttributeValue.fromS(serviceAccount));
        item.put("roleArn", AttributeValue.fromS(roleArn));
        item.put("createdAt", AttributeValue.fromS(now));

        dynamoDb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
        LOG.infof("Created association %s: %s/%s -> %s", associationId, namespace, serviceAccount, roleArn);

        // Manage trust policy on target role
        TrustPolicyService.TrustPolicyResult trustResult =
            trustPolicyService.addTrustStatement(roleArn, clusterName, namespace, serviceAccount);

        Map<String, String> result = new HashMap<>(Map.of(
            "associationId", associationId, "clusterName", clusterName,
            "namespace", namespace, "serviceAccount", serviceAccount,
            "roleArn", roleArn, "createdAt", now,
            "trustPolicyStatus", trustResult.status()));
        if (trustResult.statement() != null)
            result.put("requiredTrustPolicyStatement", trustResult.statement());
        return result;
    }

    public List<Map<String, String>> listAssociations(String clusterName,
                                                       String namespace, String serviceAccount) {
        Map<String, AttributeValue> exprValues = new HashMap<>();
        String keyCondition = "PK = :pk";
        exprValues.put(":pk", AttributeValue.fromS("CLUSTER#" + clusterName));

        if (namespace != null && !namespace.isBlank()) {
            keyCondition += " AND begins_with(SK, :skPrefix)";
            exprValues.put(":skPrefix", AttributeValue.fromS(namespace + "#"));
        }

        QueryRequest.Builder queryBuilder = QueryRequest.builder()
            .tableName(tableName)
            .keyConditionExpression(keyCondition)
            .expressionAttributeValues(exprValues);

        if (serviceAccount != null && !serviceAccount.isBlank()) {
            queryBuilder.filterExpression("serviceAccount = :sa");
            exprValues.put(":sa", AttributeValue.fromS(serviceAccount));
            queryBuilder.expressionAttributeValues(exprValues);
        }

        return dynamoDb.query(queryBuilder.build()).items().stream().map(this::itemToMap).toList();
    }

    public Map<String, String> describeAssociation(String clusterName, String associationId) {
        ScanResponse response = dynamoDb.scan(ScanRequest.builder()
            .tableName(tableName)
            .filterExpression("PK = :pk AND associationId = :aid")
            .expressionAttributeValues(Map.of(
                ":pk", AttributeValue.fromS("CLUSTER#" + clusterName),
                ":aid", AttributeValue.fromS(associationId)))
            .build());
        return response.items().isEmpty() ? null : itemToMap(response.items().getFirst());
    }

    public void deleteAssociation(String clusterName, String associationId) {
        Map<String, String> assoc = describeAssociation(clusterName, associationId);
        if (assoc == null) throw new IllegalArgumentException("Association not found: " + associationId);

        // Remove trust policy statement from target role
        trustPolicyService.removeTrustStatement(
            assoc.get("roleArn"), clusterName, assoc.get("namespace"), assoc.get("serviceAccount"));

        dynamoDb.deleteItem(DeleteItemRequest.builder()
            .tableName(tableName)
            .key(Map.of(
                "PK", AttributeValue.fromS("CLUSTER#" + clusterName),
                "SK", AttributeValue.fromS(assoc.get("namespace") + "#" + assoc.get("serviceAccount"))))
            .build());
        LOG.infof("Deleted association %s from cluster %s", associationId, clusterName);
    }

    private Map<String, AttributeValue> getAssociationItem(String clusterName,
                                                            String namespace, String serviceAccount) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(tableName)
            .key(Map.of(
                "PK", AttributeValue.fromS("CLUSTER#" + clusterName),
                "SK", AttributeValue.fromS(namespace + "#" + serviceAccount)))
            .build());
        return (response.hasItem() && !response.item().isEmpty()) ? response.item() : null;
    }

    private Map<String, String> itemToMap(Map<String, AttributeValue> item) {
        Map<String, String> result = new HashMap<>();
        for (String key : List.of("associationId", "clusterName", "namespace",
                                   "serviceAccount", "roleArn", "createdAt")) {
            if (item.containsKey(key)) result.put(key, item.get(key).s());
        }
        return result;
    }

    private static final String BROKER_ROLE_NAME = "ECPCredentialBroker";

    void validateRoleExists(String roleArn) {
        String roleName = roleArn.contains("/") ? roleArn.substring(roleArn.lastIndexOf("/") + 1) : roleArn;
        GetRoleResponse response;
        try {
            response = iamClient.getRole(GetRoleRequest.builder().roleName(roleName).build());
        } catch (NoSuchEntityException e) {
            throw new IllegalArgumentException("Role provided in the request does not exist: " + roleArn);
        }
        String trustPolicy = response.role().assumeRolePolicyDocument();
        if (trustPolicy == null || trustPolicy.isBlank())
            throw new IllegalArgumentException("Role trust policy is empty: " + roleArn);
        String decoded = java.net.URLDecoder.decode(trustPolicy, java.nio.charset.StandardCharsets.UTF_8);
        if (!decoded.contains("sts:AssumeRole") && !decoded.contains("sts:*"))
            throw new IllegalArgumentException("Role trust policy does not allow sts:AssumeRole: " + roleArn);
        // Broker principal check — warn only; trust policy will be applied after validation
        if (!decoded.contains(BROKER_ROLE_NAME))
            LOG.infof("Role %s trust policy does not yet reference %s — will be added if role is tagged ecp-managed=true", roleArn, BROKER_ROLE_NAME);
    }
}
