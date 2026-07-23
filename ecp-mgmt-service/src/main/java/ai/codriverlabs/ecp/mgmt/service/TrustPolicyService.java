package ai.codriverlabs.ecp.mgmt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Manages trust policy statements on target roles for Express Compute Workload Identity.
 * Append-only on CREATE, targeted removal on DELETE.
 */
@ApplicationScoped
public class TrustPolicyService {

    private static final Logger LOG = Logger.getLogger(TrustPolicyService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BROKER_ROLE_NAME = "ECPCredentialBroker";
    private static final String ECP_MANAGED_TAG = "ecp-managed";

    @Inject IamClient iamClient;

    public record TrustPolicyResult(String status, String statement) {
        public static TrustPolicyResult applied() { return new TrustPolicyResult("APPLIED", null); }
        public static TrustPolicyResult alreadyPresent() { return new TrustPolicyResult("ALREADY_PRESENT", null); }
        public static TrustPolicyResult manualRequired(String stmt) { return new TrustPolicyResult("MANUAL_ACTION_REQUIRED", stmt); }
    }

    /**
     * Appends a scoped trust statement to the target role. Returns status.
     */
    public TrustPolicyResult addTrustStatement(String roleArn, String clusterName,
                                                String namespace, String serviceAccount) {
        String roleName = extractRoleName(roleArn);
        String sid = buildSid(clusterName, namespace, serviceAccount);
        String requiredStatement = buildStatement(clusterName, namespace, serviceAccount);

        if (!isRoleTagged(roleName)) {
            LOG.infof("Role %s not tagged %s=true — returning manual action required", roleName, ECP_MANAGED_TAG);
            return TrustPolicyResult.manualRequired(requiredStatement);
        }

        try {
            GetRoleResponse roleResp = iamClient.getRole(GetRoleRequest.builder().roleName(roleName).build());
            String currentPolicy = URLDecoder.decode(roleResp.role().assumeRolePolicyDocument(), StandardCharsets.UTF_8);
            JsonNode policyNode = MAPPER.readTree(currentPolicy);
            ArrayNode statements = (ArrayNode) policyNode.get("Statement");

            // Idempotent: check if Sid already exists
            for (JsonNode stmt : statements) {
                if (stmt.has("Sid") && sid.equals(stmt.get("Sid").asText())) {
                    LOG.infof("Trust statement %s already present on role %s", sid, roleName);
                    return TrustPolicyResult.alreadyPresent();
                }
            }

            // Append new statement
            JsonNode newStmt = MAPPER.readTree(requiredStatement);
            statements.add(newStmt);

            iamClient.updateAssumeRolePolicy(UpdateAssumeRolePolicyRequest.builder()
                .roleName(roleName)
                .policyDocument(MAPPER.writeValueAsString(policyNode))
                .build());

            LOG.infof("Added trust statement %s to role %s", sid, roleName);
            return TrustPolicyResult.applied();
        } catch (MalformedPolicyDocumentException e) {
            LOG.errorf("Malformed policy on role %s: %s", roleName, e.getMessage());
            return TrustPolicyResult.manualRequired(requiredStatement);
        } catch (Exception e) {
            LOG.warnf("Cannot update trust policy on role %s: %s — returning manual action", roleName, e.getMessage());
            return TrustPolicyResult.manualRequired(requiredStatement);
        }
    }

    /**
     * Removes the matching trust statement from the target role on association delete.
     */
    public void removeTrustStatement(String roleArn, String clusterName,
                                      String namespace, String serviceAccount) {
        String roleName = extractRoleName(roleArn);
        String sid = buildSid(clusterName, namespace, serviceAccount);

        if (!isRoleTagged(roleName)) return; // nothing to remove if we never managed it

        try {
            GetRoleResponse roleResp = iamClient.getRole(GetRoleRequest.builder().roleName(roleName).build());
            String currentPolicy = URLDecoder.decode(roleResp.role().assumeRolePolicyDocument(), StandardCharsets.UTF_8);
            JsonNode policyNode = MAPPER.readTree(currentPolicy);
            ArrayNode statements = (ArrayNode) policyNode.get("Statement");

            int removeIdx = -1;
            for (int i = 0; i < statements.size(); i++) {
                if (statements.get(i).has("Sid") && sid.equals(statements.get(i).get("Sid").asText())) {
                    removeIdx = i;
                    break;
                }
            }

            if (removeIdx < 0) {
                LOG.infof("Trust statement %s not found on role %s — nothing to remove", sid, roleName);
                return;
            }

            statements.remove(removeIdx);
            iamClient.updateAssumeRolePolicy(UpdateAssumeRolePolicyRequest.builder()
                .roleName(roleName)
                .policyDocument(MAPPER.writeValueAsString(policyNode))
                .build());
            LOG.infof("Removed trust statement %s from role %s", sid, roleName);
        } catch (Exception e) {
            LOG.warnf("Cannot remove trust statement from role %s: %s", roleName, e.getMessage());
        }
    }

    /**
     * Validates the trust policy has the broker principal. Returns the broker role ARN.
     */
    public void validateTrustPolicy(String roleArn, String decodedTrustPolicy) {
        if (!decodedTrustPolicy.contains(BROKER_ROLE_NAME))
            throw new IllegalArgumentException(
                "Role trust policy must reference principal " + BROKER_ROLE_NAME +
                ". Tag the role with '" + ECP_MANAGED_TAG + "=true' and Express Compute will manage the trust policy automatically.");
    }

    public String buildStatement(String clusterName, String namespace, String serviceAccount) {
        String brokerArn = buildBrokerArn();
        ObjectNode stmt = MAPPER.createObjectNode();
        stmt.put("Sid", buildSid(clusterName, namespace, serviceAccount));
        stmt.put("Effect", "Allow");
        ObjectNode principal = stmt.putObject("Principal");
        principal.put("AWS", brokerArn);
        ArrayNode actions = stmt.putArray("Action");
        actions.add("sts:AssumeRole");
        actions.add("sts:TagSession");
        actions.add("sts:SetSourceIdentity");
        ObjectNode condition = stmt.putObject("Condition");
        ObjectNode stringEquals = condition.putObject("StringEquals");
        stringEquals.put("aws:RequestTag/eks-cluster-name", clusterName);
        stringEquals.put("aws:RequestTag/kubernetes-namespace", namespace);
        stringEquals.put("aws:RequestTag/kubernetes-service-account", serviceAccount);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(stmt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isRoleTagged(String roleName) {
        try {
            ListRoleTagsResponse tagsResp = iamClient.listRoleTags(
                ListRoleTagsRequest.builder().roleName(roleName).build());
            return tagsResp.tags().stream()
                .anyMatch(t -> ECP_MANAGED_TAG.equals(t.key()) && "true".equals(t.value()));
        } catch (Exception e) {
            return false;
        }
    }

    private String buildBrokerArn() {
        String account = System.getenv().getOrDefault("AWS_ACCOUNT_ID",
            System.getenv().getOrDefault("CDK_DEFAULT_ACCOUNT", "000000000000"));
        return "arn:aws:iam::" + account + ":role/" + BROKER_ROLE_NAME;
    }

    static String buildSid(String clusterName, String namespace, String serviceAccount) {
        // Sid allows only [A-Za-z0-9] — replace dashes/dots with empty
        String raw = "AllowECP" + clusterName + namespace + serviceAccount;
        return raw.replaceAll("[^A-Za-z0-9]", "");
    }

    private static String extractRoleName(String roleArn) {
        return roleArn.contains("/") ? roleArn.substring(roleArn.lastIndexOf("/") + 1) : roleArn;
    }
}
