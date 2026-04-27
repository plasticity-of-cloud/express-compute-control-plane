package cloud.plasticity.eksdx.lambda.service;

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
 * Table: eks-dx-associations (PK: CLUSTER#clusterName, SK: namespace#serviceAccount)
 */
@ApplicationScoped
public class DynamoDbAssociationService {

    private static final Logger LOG = Logger.getLogger(DynamoDbAssociationService.class);

    @Inject DynamoDbClient dynamoDb;

    @Inject IamClient iamClient;

    @ConfigProperty(name = "eks-dx.associations-table")
    String tableName;

    public String getRoleArn(String clusterName, String namespace, String serviceAccount) {
        Map<String, AttributeValue> item = getAssociationItem(clusterName, namespace, serviceAccount);
        if (item != null && item.containsKey("roleArn")) {
            return item.get("roleArn").s();
        }
        return null;
    }

    public String getAssociationId(String clusterName, String namespace, String serviceAccount) {
        Map<String, AttributeValue> item = getAssociationItem(clusterName, namespace, serviceAccount);
        if (item != null && item.containsKey("associationId")) {
            return item.get("associationId").s();
        }
        return null;
    }

    public Map<String, String> createAssociation(String clusterName, String namespace,
                                                   String serviceAccount, String roleArn) {
        if (clusterName == null || clusterName.isBlank()) {
            throw new IllegalArgumentException("clusterName is required");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace is required");
        }
        if (serviceAccount == null || serviceAccount.isBlank()) {
            throw new IllegalArgumentException("serviceAccount is required");
        }
        if (roleArn == null || roleArn.isBlank()) {
            throw new IllegalArgumentException("roleArn is required");
        }

        // Validate IAM role exists (matches real EKS CreatePodIdentityAssociation behavior)
        // See: https://docs.aws.amazon.com/eks/latest/APIReference/API_CreatePodIdentityAssociation.html
        validateRoleExists(roleArn);

        // Check for duplicate
        if (getRoleArn(clusterName, namespace, serviceAccount) != null) {
            throw new IllegalStateException("Association already exists for " +
                namespace + "/" + serviceAccount + " in cluster " + clusterName);
        }

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

        dynamoDb.putItem(PutItemRequest.builder()
            .tableName(tableName)
            .item(item)
            .build());

        LOG.infof("Created association %s: %s/%s -> %s", associationId, namespace, serviceAccount, roleArn);

        return Map.of(
            "associationId", associationId,
            "clusterName", clusterName,
            "namespace", namespace,
            "serviceAccount", serviceAccount,
            "roleArn", roleArn,
            "createdAt", now
        );
    }

    public List<Map<String, String>> listAssociations(String clusterName,
                                                       String namespace, String serviceAccount) {
        Map<String, String> exprNames = new HashMap<>();
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

        // Post-filter by serviceAccount if both namespace and serviceAccount are specified
        if (serviceAccount != null && !serviceAccount.isBlank()) {
            queryBuilder.filterExpression("serviceAccount = :sa");
            exprValues.put(":sa", AttributeValue.fromS(serviceAccount));
            queryBuilder.expressionAttributeValues(exprValues);
        }

        QueryResponse response = dynamoDb.query(queryBuilder.build());

        return response.items().stream()
            .map(this::itemToMap)
            .toList();
    }

    public Map<String, String> describeAssociation(String clusterName, String associationId) {
        // Scan with filter since associationId is not a key
        ScanResponse response = dynamoDb.scan(ScanRequest.builder()
            .tableName(tableName)
            .filterExpression("PK = :pk AND associationId = :aid")
            .expressionAttributeValues(Map.of(
                ":pk", AttributeValue.fromS("CLUSTER#" + clusterName),
                ":aid", AttributeValue.fromS(associationId)))
            .build());

        if (response.items().isEmpty()) {
            return null;
        }
        return itemToMap(response.items().getFirst());
    }

    public void deleteAssociation(String clusterName, String associationId) {
        Map<String, String> assoc = describeAssociation(clusterName, associationId);
        if (assoc == null) {
            throw new IllegalArgumentException("Association not found: " + associationId);
        }

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

        if (response.hasItem() && !response.item().isEmpty()) {
            return response.item();
        }
        return null;
    }

    private Map<String, String> itemToMap(Map<String, AttributeValue> item) {
        Map<String, String> result = new HashMap<>();
        for (String key : List.of("associationId", "clusterName", "namespace",
                                   "serviceAccount", "roleArn", "createdAt")) {
            if (item.containsKey(key)) {
                result.put(key, item.get(key).s());
            }
        }
        return result;
    }

    /**
     * Validates that the IAM role exists and has a trust policy that allows
     * it to be assumed. Matches real EKS behavior where
     * CreatePodIdentityAssociation returns InvalidParameterException if the
     * role does not exist or has an incorrect trust policy.
     *
     * <p>Real EKS requires the role to trust {@code pods.eks.amazonaws.com}.
     * EKS-DX accepts roles that trust either:
     * <ul>
     *   <li>{@code pods.eks.amazonaws.com} (EKS-compatible roles)</li>
     *   <li>Any AWS principal with {@code sts:AssumeRole} (Lambda execution role)</li>
     * </ul>
     *
     * @see <a href="https://docs.aws.amazon.com/eks/latest/APIReference/API_CreatePodIdentityAssociation.html">
     *      CreatePodIdentityAssociation API</a>
     * @see <a href="https://docs.aws.amazon.com/eks/latest/userguide/pod-id-association.html">
     *      EKS Pod Identity association setup</a>
     */
    void validateRoleExists(String roleArn) {
        String roleName = roleArn.contains("/")
            ? roleArn.substring(roleArn.lastIndexOf("/") + 1)
            : roleArn;

        GetRoleResponse response;
        try {
            response = iamClient.getRole(GetRoleRequest.builder().roleName(roleName).build());
        } catch (NoSuchEntityException e) {
            throw new IllegalArgumentException("Role provided in the request does not exist: " + roleArn);
        }

        // Validate trust policy allows sts:AssumeRole
        String trustPolicy = response.role().assumeRolePolicyDocument();
        if (trustPolicy == null || trustPolicy.isBlank()) {
            throw new IllegalArgumentException(
                "Role trust policy is empty. The role must allow sts:AssumeRole: " + roleArn);
        }

        // URL-decoded trust policy from IAM API
        String decoded = java.net.URLDecoder.decode(trustPolicy, java.nio.charset.StandardCharsets.UTF_8);
        if (!decoded.contains("sts:AssumeRole") && !decoded.contains("sts:*")) {
            throw new IllegalArgumentException(
                "Role trust policy does not allow sts:AssumeRole. " +
                "The role must trust the EKS-DX Lambda execution role or pods.eks.amazonaws.com: " + roleArn);
        }
    }
}
