package ai.codriverlabs.eksdx.tenant.service;

import ai.codriverlabs.eksdx.tenant.model.TenantItem;
import ai.codriverlabs.eksdx.tenant.model.TenantProgress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateKeyPairRequest;
import software.amazon.awssdk.services.ec2.model.CreateKeyPairResponse;
import software.amazon.awssdk.services.ec2.model.DeleteKeyPairRequest;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.DeleteRoleRequest;
import software.amazon.awssdk.services.iam.model.DeleteRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DeleteSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Provisions and deprovisions per-tenant kubeadm clusters.
 *
 * Provisioning flow (all async after 202):
 *   1. Generate RSA-2048 SA signing key → Secrets Manager
 *   2. ec2:CreateKeyPair (SSH) → Secrets Manager
 *   3. iam:CreateRole with least-privilege inline policy
 *   4. ec2:RunInstances via Launch Template
 *   5. DynamoDB.put initial state
 *
 * The EC2 instance user data drives the rest of the state machine
 * and writes progress directly to DynamoDB via its instance profile.
 */
@ApplicationScoped
public class TenantProvisioningService {

    private static final Logger LOG = Logger.getLogger(TenantProvisioningService.class);

    @Inject DynamoDbClient dynamoDb;
    @Inject IamClient iam;
    @Inject StsClient sts;

    // EC2 and SecretsManager have no Quarkus extension in the BOM — create via default credential chain
    private final Ec2Client ec2 = Ec2Client.create();
    private final SecretsManagerClient secretsManager = SecretsManagerClient.create();

    @ConfigProperty(name = "eks-dx.tenants-table")
    String tenantsTable;

    @ConfigProperty(name = "eks-dx.clusters-table")
    String clustersTable;

    @ConfigProperty(name = "eks-dx.tenant.launch-template-id")
    String launchTemplateId;

    @ConfigProperty(name = "eks-dx.tenant.subnet-id")
    String subnetId;

    // -------------------------------------------------------------------------
    // Provision
    // -------------------------------------------------------------------------

    public String provision(String tenantId) {
        LOG.infof("Provisioning tenant: %s", tenantId);

        String region = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        String accountId = sts.getCallerIdentity(GetCallerIdentityRequest.builder().build()).account();

        // 1. Generate RSA-2048 SA signing key
        String signingKeyPem = generateRsaPrivateKeyPem();
        String signingKeyArn = secretsManager.createSecret(CreateSecretRequest.builder()
            .name("eks-dx/tenant/" + tenantId + "/signing-key")
            .secretString(signingKeyPem)
            .build()).arn();
        LOG.infof("Stored signing key for tenant %s: %s", tenantId, signingKeyArn);

        // 2. EC2 key pair for SSH
        CreateKeyPairResponse keyPairResp = ec2.createKeyPair(CreateKeyPairRequest.builder()
            .keyName("eks-dx-tenant-" + tenantId)
            .build());
        String sshKeyArn = secretsManager.createSecret(CreateSecretRequest.builder()
            .name("eks-dx/tenant/" + tenantId + "/ssh-key")
            .secretString(keyPairResp.keyMaterial())
            .build()).arn();
        LOG.infof("Stored SSH key for tenant %s: %s", tenantId, sshKeyArn);

        // 3. IAM role with least-privilege inline policy
        String roleName = "eks-dx-tenant-" + tenantId + "-instance-role";
        iam.createRole(CreateRoleRequest.builder()
            .roleName(roleName)
            .assumeRolePolicyDocument(ec2TrustPolicy())
            .build());
        iam.putRolePolicy(PutRolePolicyRequest.builder()
            .roleName(roleName)
            .policyName("eks-dx-tenant-policy")
            .policyDocument(tenantInstancePolicy(tenantId, region, accountId))
            .build());
        LOG.infof("Created IAM role %s for tenant %s", roleName, tenantId);

        // 4. Launch EC2 instance
        RunInstancesResponse runResp = ec2.runInstances(RunInstancesRequest.builder()
            .launchTemplate(LaunchTemplateSpecification.builder()
                .launchTemplateId(launchTemplateId).build())
            .subnetId(subnetId)
            .keyName("eks-dx-tenant-" + tenantId)
            .iamInstanceProfile(IamInstanceProfileSpecification.builder()
                .name(roleName).build())
            .minCount(1).maxCount(1)
            .tagSpecifications(TagSpecification.builder()
                .resourceType(ResourceType.INSTANCE)
                .tags(Tag.builder().key("eks-dx-tenant").value(tenantId).build())
                .build())
            .build());
        String instanceId = runResp.instances().getFirst().instanceId();
        LOG.infof("Launched EC2 instance %s for tenant %s", instanceId, tenantId);

        // 5. Write initial DynamoDB state
        String now = Instant.now().toString();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("tenantId", AttributeValue.fromS(tenantId));
        item.put("instanceId", AttributeValue.fromS(instanceId));
        item.put("state", AttributeValue.fromS("provisioning"));
        item.put("phase", AttributeValue.fromS("EC2 instance launched"));
        item.put("progress", AttributeValue.fromN("0"));
        item.put("sshKeySecretArn", AttributeValue.fromS(sshKeyArn));
        item.put("updatedAt", AttributeValue.fromS(now));
        dynamoDb.putItem(PutItemRequest.builder().tableName(tenantsTable).item(item).build());

        return tenantId;
    }

    // -------------------------------------------------------------------------
    // Get state
    // -------------------------------------------------------------------------

    public TenantItem getState(String tenantId) {
        GetItemResponse resp = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(tenantsTable)
            .key(Map.of("tenantId", AttributeValue.fromS(tenantId)))
            .build());
        if (!resp.hasItem() || resp.item().isEmpty())
            throw new IllegalArgumentException("Tenant not found: " + tenantId);
        return itemToTenant(resp.item());
    }

    public TenantProgress getProgress(String tenantId) {
        TenantItem item = getState(tenantId);
        long elapsed = Instant.now().getEpochSecond()
            - Instant.parse(item.updatedAt()).getEpochSecond();
        String sshPrivateKey = null;
        if ("ready".equals(item.state()) && item.sshKeySecretArn() != null) {
            sshPrivateKey = secretsManager.getSecretValue(
                GetSecretValueRequest.builder().secretId(item.sshKeySecretArn()).build()
            ).secretString();
        }
        return new TenantProgress(item.state(), item.phase(), item.progress(),
            item.publicIp(), elapsed, item.error(), sshPrivateKey);
    }

    // -------------------------------------------------------------------------
    // Deprovision
    // -------------------------------------------------------------------------

    public void deprovision(String tenantId) {
        LOG.infof("Deprovisioning tenant: %s", tenantId);
        TenantItem tenant = getState(tenantId);

        // 1. Terminate EC2 instance
        if (tenant.instanceId() != null) {
            ec2.terminateInstances(TerminateInstancesRequest.builder()
                .instanceIds(tenant.instanceId()).build());
            LOG.infof("Terminated instance %s", tenant.instanceId());
        }

        // 2. Remove cluster registration from eks-dx-clusters table
        dynamoDb.deleteItem(DeleteItemRequest.builder()
            .tableName(clustersTable)
            .key(Map.of("clusterName", AttributeValue.fromS(tenantId)))
            .build());

        // 3. Delete secrets
        deleteSecretIfExists("eks-dx/tenant/" + tenantId + "/signing-key");
        deleteSecretIfExists("eks-dx/tenant/" + tenantId + "/ssh-key");

        // 4. Delete EC2 key pair
        try {
            ec2.deleteKeyPair(DeleteKeyPairRequest.builder()
                .keyName("eks-dx-tenant-" + tenantId).build());
        } catch (Exception e) {
            LOG.warnf("Could not delete key pair for tenant %s: %s", tenantId, e.getMessage());
        }

        // 5. Delete IAM role
        String roleName = "eks-dx-tenant-" + tenantId + "-instance-role";
        try {
            iam.deleteRolePolicy(DeleteRolePolicyRequest.builder()
                .roleName(roleName).policyName("eks-dx-tenant-policy").build());
            iam.deleteRole(DeleteRoleRequest.builder().roleName(roleName).build());
            LOG.infof("Deleted IAM role %s", roleName);
        } catch (Exception e) {
            LOG.warnf("Could not delete IAM role %s: %s", roleName, e.getMessage());
        }

        // 6. Delete tenant DynamoDB item
        dynamoDb.deleteItem(DeleteItemRequest.builder()
            .tableName(tenantsTable)
            .key(Map.of("tenantId", AttributeValue.fromS(tenantId)))
            .build());

        LOG.infof("Deprovisioned tenant: %s", tenantId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void deleteSecretIfExists(String secretId) {
        try {
            secretsManager.deleteSecret(DeleteSecretRequest.builder()
                .secretId(secretId)
                .forceDeleteWithoutRecovery(true)
                .build());
        } catch (Exception e) {
            LOG.warnf("Could not delete secret %s: %s", secretId, e.getMessage());
        }
    }

    private String generateRsaPrivateKeyPem() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair kp = gen.generateKeyPair();
            byte[] encoded = kp.getPrivate().getEncoded();
            return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END PRIVATE KEY-----\n";
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RSA key pair", e);
        }
    }

    private String ec2TrustPolicy() {
        return """
            {
              "Version": "2012-10-17",
              "Statement": [{
                "Effect": "Allow",
                "Principal": { "Service": "ec2.amazonaws.com" },
                "Action": "sts:AssumeRole"
              }]
            }
            """;
    }

    private String tenantInstancePolicy(String tenantId, String region, String accountId) {
        return """
            {
              "Version": "2012-10-17",
              "Statement": [
                {
                  "Effect": "Allow",
                  "Action": "secretsmanager:GetSecretValue",
                  "Resource": "arn:aws:secretsmanager:%s:%s:secret:eks-dx/tenant/%s/*"
                },
                {
                  "Effect": "Allow",
                  "Action": "execute-api:Invoke",
                  "Resource": "arn:aws:execute-api:%s:%s:*/*/POST/clusters/%s"
                },
                {
                  "Effect": "Allow",
                  "Action": "dynamodb:UpdateItem",
                  "Resource": "arn:aws:dynamodb:%s:%s:table/eks-dx-tenants",
                  "Condition": {
                    "ForAllValues:StringEquals": { "dynamodb:LeadingKeys": ["%s"] }
                  }
                }
              ]
            }
            """.formatted(region, accountId, tenantId,
                          region, accountId, tenantId,
                          region, accountId, tenantId);
    }

    private TenantItem itemToTenant(Map<String, AttributeValue> item) {
        return new TenantItem(
            s(item, "tenantId"),
            s(item, "instanceId"),
            s(item, "state"),
            s(item, "phase"),
            item.containsKey("progress") ? Integer.parseInt(item.get("progress").n()) : 0,
            s(item, "publicIp"),
            s(item, "sshKeySecretArn"),
            s(item, "updatedAt"),
            s(item, "error")
        );
    }

    private String s(Map<String, AttributeValue> item, String key) {
        return item.containsKey(key) ? item.get(key).s() : null;
    }
}
