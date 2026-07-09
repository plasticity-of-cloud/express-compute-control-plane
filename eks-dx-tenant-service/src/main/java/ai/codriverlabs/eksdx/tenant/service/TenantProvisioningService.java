package ai.codriverlabs.eksdx.tenant.service;

import ai.codriverlabs.eksdx.tenant.TenantNaming;
import ai.codriverlabs.eksdx.tenant.exception.ClusterAlreadyExistsException;
import ai.codriverlabs.eksdx.tenant.model.TenantItem;
import ai.codriverlabs.eksdx.tenant.model.TenantProgress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
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
import software.amazon.awssdk.services.iam.model.AddRoleToInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.CreateInstanceProfileRequest;
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
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.cloudwatchevents.CloudWatchEventsClient;
import software.amazon.awssdk.services.cloudwatchevents.model.PutRuleRequest;
import software.amazon.awssdk.services.cloudwatchevents.model.PutTargetsRequest;
import software.amazon.awssdk.services.cloudwatchevents.model.Target;

import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provisions and deprovisions per-tenant kubeadm clusters.
 *
 * Provisioning flow (all async after 202):
 *   1. Generate RSA-2048 SA signing key → Secrets Manager
 *   2. ec2:CreateKeyPair (SSH) → Secrets Manager
 *   3. iam:CreateRole with least-privilege inline policy
 *   4. Create per-tenant SQS FIFO progress queue
 *   5. ec2:RunInstances via Launch Template
 *   6. DynamoDB.put initial state
 *
 * The EC2 instance user data drives the rest of the state machine.
 * Boot scripts write progress to the per-tenant SQS FIFO queue;
 * the SSE Lambda (TenantStreamResource) polls SQS, validates,
 * persists to DynamoDB, and deletes the queue on terminal state.
 */
@ApplicationScoped
public class TenantProvisioningService {

    private static final Logger LOG = Logger.getLogger(TenantProvisioningService.class);

    @Inject DynamoDbClient dynamoDb;
    @Inject StsClient sts;
    @Inject IamClient iam;
    @Inject TenantNetworkService networkService;
    @Inject TenantIamService iamService;
    @Inject TenantEc2Service ec2Service;
    @Inject TenantDlmService dlmService;
    @Inject TenantCryptoService cryptoService;

    @Inject Ec2Client ec2;
    @Inject SecretsManagerClient secretsManager;
    @Inject SqsClient sqs;
    @Inject CloudWatchEventsClient events;

    @ConfigProperty(name = "eks-d-xpress.tenants-table")
    String tenantsTable;

    @ConfigProperty(name = "eks-d-xpress.clusters-table")
    String clustersTable;

    @ConfigProperty(name = "eks-d-xpress.tenant.lt-arm64-ondemand")
    String ltArm64Ondemand;

    @ConfigProperty(name = "eks-d-xpress.tenant.lt-arm64-spot")
    String ltArm64Spot;

    @ConfigProperty(name = "eks-d-xpress.tenant.lt-x86-ondemand")
    String ltX86Ondemand;

    @ConfigProperty(name = "eks-d-xpress.tenant.lt-x86-spot")
    String ltX86Spot;

    @ConfigProperty(name = "eks-d-xpress.tenant.vpc-id")
    String vpcId;

    @ConfigProperty(name = "eks-d-xpress.tenant.availability-zone", defaultValue = "")
    String availabilityZone;

    @Inject TenantIdGenerator tenantIdGenerator;

    // -------------------------------------------------------------------------
    // Provision
    // -------------------------------------------------------------------------

    /**
     * Provision a managed or unmanaged tenant.
     *
     * @param clusterName    user-provided, validated cluster name
     * @param managed        true = provision EC2+EKS-D; false = create record only
     * @param idcUserId      IAM Identity Center user ID (used to derive tenantId)
     * @param ownerArn       caller IAM ARN
     * @return system-derived tenantId
     */
    public String provision(String clusterName, boolean managed, String idcUserId, String ownerArn,
                            String arch, String ec2PricingModel, String k8sVersion,
                            boolean assignElasticIp, int diskSizeGb, String sshCidr) {
        // Fail fast: check uniqueness before creating any AWS resources.
        // For managed mode this is critical — without it the stack creates subnets, IAM
        // roles, EC2, etc. before hitting DynamoDB, all of which need rolling back.
        if (clusterExists(clusterName)) {
            throw new ClusterAlreadyExistsException(clusterName);
        }

        String createdAt = Instant.now().toString();
        String tenantId = tenantIdGenerator.generate(idcUserId, createdAt);

        LOG.infof("Provisioning tenant: %s (cluster=%s, managed=%s)", tenantId, clusterName, managed);

        if (!managed) {
            writeInitialRecord(tenantId, clusterName, false, idcUserId, ownerArn, createdAt, null, null, null);
            return tenantId;
        }

        String launchTemplateId = resolveLaunchTemplate(arch, ec2PricingModel);
        String region = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        String accountId = sts.getCallerIdentity(GetCallerIdentityRequest.builder().build()).account();

        // Track created resources for rollback on failure
        var created = new ProvisionedResources();

        try {
            // 1. Network isolation (per-tenant subnets + security group)
            String az = availabilityZone.isEmpty() || "auto".equals(availabilityZone) ? region + "a" : availabilityZone;
            TenantNetworkService.NetworkResult network = networkService.createTenantNetwork(
                tenantId, clusterName, vpcId, az, sshCidr);
            created.network = network;

            // 2. PKI (CA + SA signing key via KMS, stored in Secrets Manager)
            TenantCryptoService.CryptoResult crypto = cryptoService.generateAndStore(tenantId);
            created.cryptoSecrets = crypto;

            // 3. SSH key pair
            CreateKeyPairResponse keyPairResp = ec2.createKeyPair(CreateKeyPairRequest.builder()
                .keyName(TenantNaming.keyPairName(tenantId))
                .tagSpecifications(software.amazon.awssdk.services.ec2.model.TagSpecification.builder()
                    .resourceType(software.amazon.awssdk.services.ec2.model.ResourceType.KEY_PAIR)
                    .tags(software.amazon.awssdk.services.ec2.model.Tag.builder().key("project").value("eks-d-xpress").build(),
                          software.amazon.awssdk.services.ec2.model.Tag.builder().key("eks-dx-tenant").value(tenantId).build())
                    .build())
                .build());
            created.keyPairName = TenantNaming.keyPairName(tenantId);

            String sshKeyArn = secretsManager.createSecret(CreateSecretRequest.builder()
                .name(TenantNaming.secretPath(tenantId, "ssh-key"))
                .secretString(keyPairResp.keyMaterial()).build()).arn();
            created.sshKeySecret = TenantNaming.secretPath(tenantId, "ssh-key");

            // 4. Pre-register cluster in DynamoDB (JWKS + issuer known before EC2 boots)
            preRegisterCluster(tenantId, clusterName, crypto);
            created.clusterPreRegistered = true;

            // 5. IAM role + instance profile
            TenantIamService.IamResult iamResult = iamService.createTenantRole(
                tenantId, clusterName, region, accountId);
            created.iamRoleName = iamResult.roleName();
            created.instanceProfileName = iamResult.instanceProfileName();

            // 6. Progress queue (per-tenant FIFO, ephemeral — deleted on provisioning completion)
            String progressQueueUrl = createProgressQueue(tenantId);
            created.progressQueueUrl = progressQueueUrl;

            // 7. SQS + EventBridge (Karpenter interruption handling)
            String queueArn = createInterruptionQueue(tenantId, region, accountId);
            created.queueUrl = queueArn;
            createEventBridgeRules(tenantId, queueArn);
            created.eventBridgeRulePrefix = tenantId;

            // 8. DLM (daily etcd backup)
            dlmService.createEtcdBackupPolicy(tenantId, clusterName, region);
            created.dlmPolicyCreated = true;

            // 9. EC2 instance launch
            TenantEc2Service.Ec2Result ec2Result = ec2Service.launchInstance(
                tenantId, clusterName, launchTemplateId,
                network.publicSubnetId(), network.securityGroupId(),
                iamResult.instanceProfileName(), TenantNaming.keyPairName(tenantId),
                region, k8sVersion, assignElasticIp, diskSizeGb, arch,
                network.controlPlaneIp(), accountId, network.vpcCidr(),
                network.publicSubnetId(), network.privateSubnetId(), created);
            created.instanceId = ec2Result.instanceId();
            created.eipAllocationId = ec2Result.eipAllocationId();

            // 9. Write initial DynamoDB state
            writeInitialRecord(tenantId, clusterName, true, idcUserId, ownerArn, createdAt,
                ec2Result.instanceId(), sshKeyArn,
                ec2PricingModel);
            if (ec2Result.eipAllocationId() != null) {
                dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tenantsTable)
                    .key(Map.of("tenantId", AttributeValue.fromS(tenantId)))
                    .updateExpression("SET eipAllocationId = :e")
                    .expressionAttributeValues(Map.of(":e", AttributeValue.fromS(ec2Result.eipAllocationId())))
                    .build());
            }

            return tenantId;
        } catch (Exception e) {
            LOG.errorf("Provisioning failed, initiating rollback for tenant %s: %s", tenantId, e.getMessage());
            rollback(tenantId, clusterName, created);
            throw e;
        }
    }

    private void writeInitialRecord(String tenantId, String clusterName, boolean managed,
                                    String idcUserId, String ownerArn, String createdAt,
                                    String instanceId, String sshKeyArn, String ec2PricingModel) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("tenantId",     AttributeValue.fromS(tenantId));
        item.put("clusterName",  AttributeValue.fromS(clusterName));
        item.put("managed",      AttributeValue.fromS(String.valueOf(managed)));
        item.put("createdAt",    AttributeValue.fromS(createdAt));
        item.put("updatedAt",    AttributeValue.fromS(createdAt));
        if (idcUserId != null)      item.put("idcUserId",  AttributeValue.fromS(idcUserId));
        if (ownerArn != null)       item.put("ownerArn",   AttributeValue.fromS(ownerArn));
        if (managed) {
            item.put("state",           AttributeValue.fromS("provisioning"));
            item.put("phase",           AttributeValue.fromS("EC2 instance launched"));
            item.put("progress",        AttributeValue.fromN("0"));
            if (instanceId != null)     item.put("instanceId",      AttributeValue.fromS(instanceId));
            if (sshKeyArn != null)      item.put("sshKeySecretArn", AttributeValue.fromS(sshKeyArn));
            if (ec2PricingModel != null) item.put("ec2PricingModel", AttributeValue.fromS(ec2PricingModel));
        }
        dynamoDb.putItem(PutItemRequest.builder().tableName(tenantsTable).item(item).build());
    }

    // -------------------------------------------------------------------------
    // Rollback — best-effort cleanup of partially-created resources
    // -------------------------------------------------------------------------

    private void rollback(String tenantId, String clusterName, ProvisionedResources created) {
        LOG.infof("Rolling back tenant %s", tenantId);

        if (created.instanceId != null) {
            try {
                ec2.terminateInstances(software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest.builder()
                    .instanceIds(created.instanceId).build());
                LOG.infof("Rollback: terminated instance %s", created.instanceId);
                // Wait for termination before deleting network resources (SG has dependency)
                ec2.waiter().waitUntilInstanceTerminated(r -> r.instanceIds(created.instanceId));
            } catch (Exception ex) { LOG.warnf("Rollback: failed to terminate instance: %s", ex.getMessage()); }
        }

        if (created.eipAllocationId != null) {
            try {
                ec2.releaseAddress(software.amazon.awssdk.services.ec2.model.ReleaseAddressRequest.builder()
                    .allocationId(created.eipAllocationId).build());
                LOG.infof("Rollback: released EIP %s", created.eipAllocationId);
            } catch (Exception ex) { LOG.warnf("Rollback: failed to release EIP: %s", ex.getMessage()); }
        }

        if (created.progressQueueUrl != null) {
            try {
                sqs.deleteQueue(software.amazon.awssdk.services.sqs.model.DeleteQueueRequest.builder()
                    .queueUrl(created.progressQueueUrl).build());
                LOG.infof("Rollback: deleted progress queue for %s", tenantId);
            } catch (Exception ex) { LOG.warnf("Rollback: failed to delete progress queue: %s", ex.getMessage()); }
        }

        if (created.dlmPolicyCreated) {
            try {
                dlmService.deleteEtcdBackupPolicy(tenantId, clusterName);
                LOG.infof("Rollback: deleted DLM policy for %s", tenantId);
            } catch (Exception ex) { LOG.warnf("Rollback: failed to delete DLM policy: %s", ex.getMessage()); }
        }

        if (created.eventBridgeRulePrefix != null) {
            try {
                deleteEventBridgeRules(created.eventBridgeRulePrefix);
                LOG.infof("Rollback: deleted EventBridge rules for %s", created.eventBridgeRulePrefix);
            } catch (Exception ex) { LOG.warnf("Rollback: failed to delete EventBridge rules: %s", ex.getMessage()); }
        }

        if (created.queueUrl != null) {
            try {
                deleteInterruptionQueue(clusterName);
                LOG.infof("Rollback: deleted SQS queue for %s", clusterName);
            } catch (Exception ex) { LOG.warnf("Rollback: failed to delete SQS queue: %s", ex.getMessage()); }
        }

        if (created.instanceProfileName != null) {
            try {
                iamService.deleteTenantRole(created.iamRoleName, created.instanceProfileName);
                LOG.infof("Rollback: deleted IAM role %s", created.iamRoleName);
            } catch (Exception ex) { LOG.warnf("Rollback: failed to delete IAM role: %s", ex.getMessage()); }
        }

        if (created.keyPairName != null) {
            try {
                ec2.deleteKeyPair(software.amazon.awssdk.services.ec2.model.DeleteKeyPairRequest.builder()
                    .keyName(created.keyPairName).build());
                LOG.infof("Rollback: deleted key pair %s", created.keyPairName);
            } catch (Exception ex) { LOG.warnf("Rollback: failed to delete key pair: %s", ex.getMessage()); }
        }

        if (created.sshKeySecret != null) {
            try {
                secretsManager.deleteSecret(DeleteSecretRequest.builder()
                    .secretId(created.sshKeySecret).forceDeleteWithoutRecovery(true).build());
                LOG.infof("Rollback: deleted secret %s", created.sshKeySecret);
            } catch (Exception ex) { LOG.warnf("Rollback: failed to delete secret: %s", ex.getMessage()); }
        }

        if (created.cryptoSecrets != null) {
            try {
                cryptoService.deleteSecrets(tenantId);
                LOG.infof("Rollback: deleted PKI secrets for %s", tenantId);
            } catch (Exception ex) { LOG.warnf("Rollback: failed to delete PKI secrets: %s", ex.getMessage()); }
        }

        if (created.clusterPreRegistered) {
            try {
                dynamoDb.deleteItem(DeleteItemRequest.builder()
                    .tableName(clustersTable)
                    .key(Map.of("clusterName", AttributeValue.fromS(clusterName)))
                    .build());
                LOG.infof("Rollback: deleted pre-registered cluster %s", clusterName);
            } catch (Exception ex) { LOG.warnf("Rollback: failed to delete cluster record: %s", ex.getMessage()); }
        }

        if (created.network != null) {
            try {
                networkService.deleteTenantNetwork(created.network);
                LOG.infof("Rollback: deleted network resources for %s", tenantId);
            } catch (Exception ex) { LOG.warnf("Rollback: failed to delete network: %s", ex.getMessage()); }
        }
    }

    /**
     * Tracks resources created during provisioning for rollback on failure.
     */
    static class ProvisionedResources {
        TenantNetworkService.NetworkResult network;
        TenantCryptoService.CryptoResult cryptoSecrets;
        String sshKeySecret;
        String keyPairName;
        String iamRoleName;
        String instanceProfileName;
        String progressQueueUrl;
        String queueUrl;
        String eventBridgeRulePrefix;
        boolean dlmPolicyCreated;
        boolean clusterPreRegistered;
        String instanceId;
        String eipAllocationId;
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

    /**
     * Persists a validated progress update from SQS to DynamoDB.
     * Called by TenantStreamResource after message validation.
     */
    public void updateProgressFromSqs(String tenantId, String state, String phase, int progress) {
        dynamoDb.updateItem(UpdateItemRequest.builder()
            .tableName(tenantsTable)
            .key(Map.of("tenantId", AttributeValue.fromS(tenantId)))
            .updateExpression("SET #s = :s, phase = :p, progress = :n, updatedAt = :t")
            .expressionAttributeNames(Map.of("#s", "state"))
            .expressionAttributeValues(Map.of(
                ":s", AttributeValue.fromS(state),
                ":p", AttributeValue.fromS(phase),
                ":n", AttributeValue.fromN(String.valueOf(progress)),
                ":t", AttributeValue.fromS(Instant.now().toString())))
            .build());
    }

    /**
    /**
     * Single-tick EC2 boot check — returns one progress event per call (no sleep).
     * Returns null if instance not found yet; a terminal event with phase "provisioning_started"
     * when instance is running with a public IP.
     */
    public TenantProgress pollEc2BootTick(String tenantId) {
        try {
            TenantItem item = getState(tenantId);
            String instanceId = item.instanceId();
            if (instanceId == null) return null;

            var resp = ec2.describeInstances(r -> r.instanceIds(instanceId));
            if (resp.reservations().isEmpty() || resp.reservations().getFirst().instances().isEmpty())
                return null;

            var inst = resp.reservations().getFirst().instances().getFirst();
            String stateName = inst.state().nameAsString();

            if ("terminated".equals(stateName) || "shutting-down".equals(stateName))
                return new TenantProgress("failed", "Instance terminated unexpectedly", 0,
                    null, 0, stateName, null);

            if ("running".equals(stateName)) {
                // Associate EIP if not yet done (no publicIp in DynamoDB yet)
                String publicIp = item.publicIp();
                if (publicIp == null || publicIp.isBlank()) {
                    if (item.eipAllocationId() != null) {
                        publicIp = ec2Service.associateEip(instanceId, item.eipAllocationId());
                        // Persist public IP to DynamoDB
                        dynamoDb.updateItem(software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.builder()
                            .tableName(tenantsTable)
                            .key(Map.of("tenantId", AttributeValue.fromS(tenantId)))
                            .updateExpression("SET publicIp = :ip, updatedAt = :t")
                            .expressionAttributeValues(Map.of(
                                ":ip", AttributeValue.fromS(publicIp),
                                ":t", AttributeValue.fromS(Instant.now().toString())))
                            .build());
                    } else {
                        publicIp = inst.publicIpAddress(); // fallback: EC2-assigned public IP
                    }
                }
                if (publicIp != null && !publicIp.isBlank())
                    return new TenantProgress("provisioning", "provisioning_started", 25,
                        publicIp, 0, null, null);
                // Running but no public IP yet — keep polling
                return new TenantProgress("provisioning", "EC2 instance running...", 10,
                    null, 0, null, null);
            }

            return new TenantProgress("provisioning", "EC2 instance " + stateName + "...", 10,
                null, 0, null, null);
        } catch (Exception e) {
            LOG.warnf("EC2 boot tick error for tenant %s: %s", tenantId, e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Stop / Resume
    // -------------------------------------------------------------------------

    public void stop(String tenantId) {
        TenantItem tenant = getState(tenantId);
        if (tenant.instanceId() == null)
            throw new IllegalArgumentException("Tenant has no instance: " + tenantId);

        String instanceState = describeInstanceState(tenant.instanceId());
        if ("stopped".equals(instanceState) || "stopping".equals(instanceState)) {
            LOG.infof("Instance %s already %s, nothing to do", tenant.instanceId(), instanceState);
            updateState(tenantId, instanceState);
            return;
        }
        if ("terminated".equals(instanceState) || "shutting-down".equals(instanceState))
            throw new IllegalArgumentException("Instance " + tenant.instanceId() + " is " + instanceState + " — cannot stop");
        if (!"running".equals(instanceState))
            throw new IllegalArgumentException("Instance " + tenant.instanceId() + " is " + instanceState + " — can only stop a running instance");

        boolean isSpot = "spot".equals(tenant.ec2PricingModel());
        ec2.stopInstances(software.amazon.awssdk.services.ec2.model.StopInstancesRequest.builder()
            .instanceIds(tenant.instanceId())
            .hibernate(isSpot)   // spot requires hibernate=true; on-demand uses cold stop
            .build());
        updateState(tenantId, isSpot ? "hibernating" : "stopping");
        LOG.infof("Stopping tenant %s (instance %s, hibernate=%s)", tenantId, tenant.instanceId(), isSpot);
    }

    public void resume(String tenantId) {
        TenantItem tenant = getState(tenantId);
        if (tenant.instanceId() == null)
            throw new IllegalArgumentException("Tenant has no instance: " + tenantId);

        String instanceState = describeInstanceState(tenant.instanceId());
        if ("running".equals(instanceState) || "pending".equals(instanceState)) {
            LOG.infof("Instance %s already %s, nothing to do", tenant.instanceId(), instanceState);
            updateState(tenantId, "running".equals(instanceState) ? "ready" : "resuming");
            return;
        }
        if ("terminated".equals(instanceState) || "shutting-down".equals(instanceState))
            throw new IllegalArgumentException("Instance " + tenant.instanceId() + " is " + instanceState + " — cannot resume");
        if (!"stopped".equals(instanceState))
            throw new IllegalArgumentException("Instance " + tenant.instanceId() + " is " + instanceState + " — can only resume a stopped instance");

        ec2.startInstances(software.amazon.awssdk.services.ec2.model.StartInstancesRequest.builder()
            .instanceIds(tenant.instanceId())
            .build());
        updateState(tenantId, "resuming");
        LOG.infof("Resuming tenant %s (instance %s)", tenantId, tenant.instanceId());
    }

    private String describeInstanceState(String instanceId) {
        var resp = ec2.describeInstances(r -> r.instanceIds(instanceId));
        if (resp.reservations().isEmpty() || resp.reservations().getFirst().instances().isEmpty())
            throw new IllegalArgumentException("Instance not found: " + instanceId);
        return resp.reservations().getFirst().instances().getFirst().state().nameAsString();
    }

    /** Look up tenant state by cluster name (clustersTable PK). */
    public TenantItem getStateByClusterName(String clusterName) {
        GetItemResponse resp = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(clustersTable)
            .key(Map.of("clusterName", AttributeValue.fromS(clusterName)))
            .build());
        if (!resp.hasItem() || resp.item().isEmpty())
            throw new IllegalArgumentException("Cluster not found: " + clusterName);
        AttributeValue tenantIdAttr = resp.item().get("tenantId");
        if (tenantIdAttr == null)
            throw new IllegalArgumentException("No tenant associated with cluster: " + clusterName);
        return getState(tenantIdAttr.s());
    }

    /** Stop a managed cluster by cluster name. Enforces ownership check. */
    public void stopByClusterName(String clusterName, String callerArn) {
        TenantItem tenant = getStateByClusterName(clusterName);
        if (callerArn != null && tenant.ownerArn() != null && !callerArn.equals(tenant.ownerArn()))
            throw new SecurityException("Not authorized to stop cluster: " + clusterName);
        stop(tenant.tenantId());
    }

    /** Resume a stopped managed cluster by cluster name. Enforces ownership check. */
    public void resumeByClusterName(String clusterName, String callerArn) {
        TenantItem tenant = getStateByClusterName(clusterName);
        if (callerArn != null && tenant.ownerArn() != null && !callerArn.equals(tenant.ownerArn()))
            throw new SecurityException("Not authorized to resume cluster: " + clusterName);
        resume(tenant.tenantId());
    }

    private void updateState(String tenantId, String state) {
        dynamoDb.updateItem(UpdateItemRequest.builder()
            .tableName(tenantsTable)
            .key(Map.of("tenantId", AttributeValue.fromS(tenantId)))
            .updateExpression("SET #s = :s, updatedAt = :t")
            .expressionAttributeNames(Map.of("#s", "state"))
            .expressionAttributeValues(Map.of(
                ":s", AttributeValue.fromS(state),
                ":t", AttributeValue.fromS(Instant.now().toString())))
            .build());
    }

    // -------------------------------------------------------------------------
    // Deprovision
    // -------------------------------------------------------------------------

    public void deprovision(String tenantId) {
        LOG.infof("Deprovisioning tenant: %s", tenantId);
        TenantItem tenant = getState(tenantId);

        // 1. Terminate EC2 instance and wait for full termination before cleaning up
        if (tenant.instanceId() != null) {
            try {
                ec2.terminateInstances(TerminateInstancesRequest.builder()
                    .instanceIds(tenant.instanceId()).build());
                LOG.infof("Terminating instance %s — waiting for terminated state...", tenant.instanceId());
                try {
                    ec2.waiter().waitUntilInstanceTerminated(r -> r.instanceIds(tenant.instanceId()));
                    LOG.infof("Instance %s terminated", tenant.instanceId());
                } catch (Exception e) {
                    LOG.warnf("Wait for termination timed out for %s, proceeding anyway: %s", tenant.instanceId(), e.getMessage());
                }
            } catch (software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
                LOG.warnf("Could not terminate instance %s (may already be gone): %s", tenant.instanceId(), e.awsErrorDetails().errorCode());
            }
        }

        // 1b. Release EIP (safe to do after instance terminated — ENI is released)
        if (tenant.eipAllocationId() != null) {
            try {
                ec2.releaseAddress(software.amazon.awssdk.services.ec2.model.ReleaseAddressRequest.builder()
                    .allocationId(tenant.eipAllocationId()).build());
                LOG.infof("Released EIP %s", tenant.eipAllocationId());
            } catch (Exception e) {
                LOG.warnf("Could not release EIP %s: %s", tenant.eipAllocationId(), e.getMessage());
            }
        }

        // 2. Remove cluster registration from eks-dx-clusters table
        if (tenant.clusterName() != null) {
            dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(clustersTable)
                .key(Map.of("clusterName", AttributeValue.fromS(tenant.clusterName())))
                .build());
            LOG.infof("Deleted cluster registration %s", tenant.clusterName());
        }

        // 3. Delete secrets
        deleteSecretIfExists("eks-d-xpress/tenant/" + tenantId + "/signing-key");
        deleteSecretIfExists("eks-d-xpress/tenant/" + tenantId + "/ssh-key");

        // 4. Delete EC2 key pair
        try {
            ec2.deleteKeyPair(DeleteKeyPairRequest.builder()
                .keyName("eks-d-xpress-tenant-" + tenantId).build());
        } catch (Exception e) {
            LOG.warnf("Could not delete key pair for tenant %s: %s", tenantId, e.getMessage());
        }

        // 5. Delete IAM role + instance profile
        String awsRegion = System.getenv("AWS_REGION");
        String roleName = "eks-d-xpress-tenant-" + tenantId + "-" + awsRegion + "-instance-role";
        try {
            iamService.deleteTenantRole(roleName, roleName);
            LOG.infof("Deleted IAM role + instance profile %s", roleName);
        } catch (Exception e) {
            LOG.warnf("Could not delete IAM role %s: %s", roleName, e.getMessage());
        }

        // 5b. Delete DLM execution role
        String dlmRoleName = "eks-d-xpress-tenant-" + tenantId + "-" + awsRegion + "-dlm";
        try {
            iam.detachRolePolicy(software.amazon.awssdk.services.iam.model.DetachRolePolicyRequest.builder()
                .roleName(dlmRoleName)
                .policyArn("arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRole")
                .build());
            iam.deleteRole(software.amazon.awssdk.services.iam.model.DeleteRoleRequest.builder()
                .roleName(dlmRoleName).build());
            LOG.infof("Deleted DLM role %s", dlmRoleName);
        } catch (Exception e) {
            LOG.warnf("Could not delete DLM role %s: %s", dlmRoleName, e.getMessage());
        }

        // 6. Delete EventBridge rules + SQS queues (interruption + progress)
        try { deleteEventBridgeRules(tenantId); } catch (Exception e) {
            LOG.warnf("Could not delete EventBridge rules for %s: %s", tenantId, e.getMessage());
        }
        try { deleteInterruptionQueue(tenantId); } catch (Exception e) {
            LOG.warnf("Could not delete SQS queue for %s: %s", tenantId, e.getMessage());
        }
        // Progress queue — normally deleted by SSE Lambda on completion, but defensive cleanup here
        deleteProgressQueue(tenantId);

        // 6b. Delete subnets (look up by tenant tag)
        try {
            var subnetResp = ec2.describeSubnets(software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest.builder()
                .filters(software.amazon.awssdk.services.ec2.model.Filter.builder()
                    .name("tag:eks-d-xpress-tenant").values(tenantId).build())
                .build());
            for (var subnet : subnetResp.subnets()) {
                ec2.deleteSubnet(software.amazon.awssdk.services.ec2.model.DeleteSubnetRequest.builder()
                    .subnetId(subnet.subnetId()).build());
                LOG.infof("Deleted subnet %s", subnet.subnetId());
            }
        } catch (Exception e) {
            LOG.warnf("Could not delete subnets for tenant %s: %s", tenantId, e.getMessage());
        }

        // 6c. Delete security group (look up by tenant tag)
        try {
            var sgResp = ec2.describeSecurityGroups(software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest.builder()
                .filters(software.amazon.awssdk.services.ec2.model.Filter.builder()
                    .name("tag:eks-d-xpress-tenant").values(tenantId).build())
                .build());
            for (var sg : sgResp.securityGroups()) {
                ec2.deleteSecurityGroup(software.amazon.awssdk.services.ec2.model.DeleteSecurityGroupRequest.builder()
                    .groupId(sg.groupId()).build());
                LOG.infof("Deleted security group %s", sg.groupId());
            }
        } catch (Exception e) {
            LOG.warnf("Could not delete security group for tenant %s: %s", tenantId, e.getMessage());
        }

        // 7. Delete DLM policy
        try { dlmService.deleteEtcdBackupPolicy(tenantId, tenant.clusterName()); } catch (Exception e) {
            LOG.warnf("Could not delete DLM policy for %s: %s", tenantId, e.getMessage());
        }

        // 8. Delete tenant DynamoDB item
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

    private String createInterruptionQueue(String clusterName, String region, String accountId) {
        String queueName = TenantNaming.queueName(clusterName);
        sqs.createQueue(software.amazon.awssdk.services.sqs.model.CreateQueueRequest.builder()
            .queueName(queueName)
            .attributes(Map.of(
                software.amazon.awssdk.services.sqs.model.QueueAttributeName.MESSAGE_RETENTION_PERIOD, "300"))
            .build());
        String queueArn = "arn:aws:sqs:" + region + ":" + accountId + ":" + queueName;
        LOG.infof("Created SQS queue %s", queueName);
        return queueArn;
    }

    /**
     * Creates a per-tenant FIFO queue for boot progress reporting.
     * Delete-first for idempotency: if a prior attempt crashed, the stale queue is removed first.
     * Queue is ephemeral — deleted by SSE Lambda on terminal state (ready/failed).
     */
    private String createProgressQueue(String tenantId) {
        String queueName = TenantNaming.progressQueueName(tenantId);
        // Delete-first: handle crash recovery / retry (stale messages from prior attempt)
        try {
            String existingUrl = sqs.getQueueUrl(software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest.builder()
                .queueName(queueName).build()).queueUrl();
            sqs.deleteQueue(software.amazon.awssdk.services.sqs.model.DeleteQueueRequest.builder()
                .queueUrl(existingUrl).build());
            LOG.infof("Deleted stale progress queue %s (crash recovery)", queueName);
            // SQS requires ~60s after deletion before recreating with same name
            try { Thread.sleep(1_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        } catch (software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException e) {
            // Expected on first attempt — no stale queue
        }

        var resp = sqs.createQueue(software.amazon.awssdk.services.sqs.model.CreateQueueRequest.builder()
            .queueName(queueName)
            .attributes(Map.of(
                software.amazon.awssdk.services.sqs.model.QueueAttributeName.FIFO_QUEUE, "true",
                software.amazon.awssdk.services.sqs.model.QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false",
                software.amazon.awssdk.services.sqs.model.QueueAttributeName.MESSAGE_RETENTION_PERIOD, "3600",
                software.amazon.awssdk.services.sqs.model.QueueAttributeName.VISIBILITY_TIMEOUT, "10"))
            .tags(Map.of(
                "eks-dx-tenant", tenantId,
                "createdAt", Instant.now().toString(),
                "purpose", "progress-reporting"))
            .build());
        LOG.infof("Created progress queue %s", queueName);
        return resp.queueUrl();
    }

    private void deleteProgressQueue(String tenantId) {
        try {
            String queueName = TenantNaming.progressQueueName(tenantId);
            String queueUrl = sqs.getQueueUrl(software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest.builder()
                .queueName(queueName).build()).queueUrl();
            sqs.deleteQueue(software.amazon.awssdk.services.sqs.model.DeleteQueueRequest.builder()
                .queueUrl(queueUrl).build());
            LOG.infof("Deleted progress queue %s", queueName);
        } catch (software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException e) {
            // Already deleted (normal — SSE Lambda deletes on completion)
        }
    }

    private void createEventBridgeRules(String clusterName, String queueArn) {
        createEventBridgeRule(TenantNaming.eventRuleName(clusterName, "spot-interruption"),
            "{\"source\":[\"aws.ec2\"],\"detail-type\":[\"EC2 Spot Instance Interruption Warning\"]}", queueArn);
        createEventBridgeRule(TenantNaming.eventRuleName(clusterName, "instance-state-change"),
            "{\"source\":[\"aws.ec2\"],\"detail-type\":[\"EC2 Instance State-change Notification\"]}", queueArn);
        createEventBridgeRule(TenantNaming.eventRuleName(clusterName, "instance-rebalance"),
            "{\"source\":[\"aws.ec2\"],\"detail-type\":[\"EC2 Instance Rebalance Recommendation\"]}", queueArn);
        LOG.infof("Created EventBridge rules for %s", clusterName);
    }

    private void createEventBridgeRule(String ruleName, String eventPattern, String targetArn) {
        events.putRule(PutRuleRequest.builder()
            .name(ruleName)
            .eventPattern(eventPattern)
            .state("ENABLED")
            .build());
        events.putTargets(PutTargetsRequest.builder()
            .rule(ruleName)
            .targets(Target.builder().id("sqs").arn(targetArn).build())
            .build());
    }

    private void deleteEventBridgeRules(String clusterName) {
        for (String suffix : List.of("spot-interruption", "instance-state-change", "instance-rebalance")) {
            String ruleName = TenantNaming.eventRuleName(clusterName, suffix);
            try {
                events.removeTargets(software.amazon.awssdk.services.cloudwatchevents.model.RemoveTargetsRequest.builder()
                    .rule(ruleName).ids("sqs").build());
                events.deleteRule(software.amazon.awssdk.services.cloudwatchevents.model.DeleteRuleRequest.builder()
                    .name(ruleName).build());
            } catch (Exception e) {
                LOG.warnf("Could not delete EventBridge rule %s: %s", ruleName, e.getMessage());
            }
        }
    }

    private void deleteInterruptionQueue(String clusterName) {
        String queueUrl = sqs.getQueueUrl(software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest.builder()
            .queueName(TenantNaming.queueName(clusterName)).build()).queueUrl();
        sqs.deleteQueue(software.amazon.awssdk.services.sqs.model.DeleteQueueRequest.builder()
            .queueUrl(queueUrl).build());
    }

    private String resolveLaunchTemplate(String arch, String pricingModel) {
        return switch (arch + "/" + pricingModel) {
            case "arm64/ondemand" -> ltArm64Ondemand;
            case "arm64/spot" -> ltArm64Spot;
            case "x86_64/ondemand" -> ltX86Ondemand;
            case "x86_64/spot" -> ltX86Spot;
            default -> throw new IllegalArgumentException("Invalid arch/pricing: " + arch + "/" + pricingModel);
        };
    }

    // -------------------------------------------------------------------------
    // Cluster existence check
    // -------------------------------------------------------------------------

    /**
     * Returns true if a cluster record with the given name already exists in DynamoDB.
     * Uses a projection to read only the key — no wasted capacity on attribute data.
     */
    boolean clusterExists(String clusterName) {
        GetItemResponse resp = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(clustersTable)
            .key(Map.of("clusterName", AttributeValue.fromS(clusterName)))
            .projectionExpression("clusterName")
            .build());
        return resp.hasItem() && !resp.item().isEmpty();
    }

    private void preRegisterCluster(String tenantId, String clusterName, TenantCryptoService.CryptoResult crypto) {
        Map<String, AttributeValue> item = Map.of(
            "clusterName", AttributeValue.fromS(clusterName),
            "jwks", AttributeValue.fromS(crypto.jwks()),
            "issuer", AttributeValue.fromS(crypto.issuer()),
            "tenantId", AttributeValue.fromS(tenantId),
            "managed", AttributeValue.fromS("true"),
            "createdAt", AttributeValue.fromS(Instant.now().toString())
        );
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                .tableName(clustersTable)
                .item(item)
                // Belt-and-suspenders: atomic guard against concurrent create-cluster calls
                // racing past the upfront clusterExists() check above.
                .conditionExpression("attribute_not_exists(clusterName)")
                .build());
        } catch (ConditionalCheckFailedException e) {
            throw new ClusterAlreadyExistsException(clusterName);
        }
        LOG.infof("Pre-registered cluster %s with JWKS in DynamoDB", clusterName);
    }

    // -------------------------------------------------------------------------
    // Self-managed cluster registration
    // -------------------------------------------------------------------------

    /**
     * Register a self-managed cluster (no EC2 provisioning, no PKI generation).
     * Stores cluster record with user-provided JWKS and issuer.
     */
    public String registerSelfManagedCluster(String clusterName, String issuer, String jwks, String ownerArn) {
        // Fail fast: check uniqueness before touching DynamoDB.
        if (clusterExists(clusterName)) {
            throw new ClusterAlreadyExistsException(clusterName);
        }

        String tenantId = tenantIdGenerator.generate(ownerArn, Instant.now().toString());

        Map<String, AttributeValue> clusterItem = new HashMap<>();
        clusterItem.put("clusterName", AttributeValue.fromS(clusterName));
        clusterItem.put("jwks", AttributeValue.fromS(jwks));
        clusterItem.put("issuer", AttributeValue.fromS(issuer));
        clusterItem.put("tenantId", AttributeValue.fromS(tenantId));
        clusterItem.put("ownerArn", AttributeValue.fromS(ownerArn));
        clusterItem.put("managed", AttributeValue.fromS("false"));
        clusterItem.put("createdAt", AttributeValue.fromS(Instant.now().toString()));
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                .tableName(clustersTable)
                .item(clusterItem)
                // Belt-and-suspenders: atomic guard against concurrent registrations.
                .conditionExpression("attribute_not_exists(clusterName)")
                .build());
        } catch (ConditionalCheckFailedException e) {
            throw new ClusterAlreadyExistsException(clusterName);
        }

        // Also write tenant record for consistency
        writeInitialRecord(tenantId, clusterName, false, ownerArn, ownerArn, Instant.now().toString(), null, null, null);

        LOG.infof("Registered self-managed cluster %s (tenant %s)", clusterName, tenantId);
        return tenantId;
    }

    // -------------------------------------------------------------------------
    // Delete cluster (unified: managed teardown or self-managed deregister)
    // -------------------------------------------------------------------------

    /**
     * Delete a cluster by name. Determines teardown scope from the stored record:
     * - managed=true → full deprovision (EC2, IAM, network, secrets, DynamoDB)
     * - managed=false → remove DynamoDB records only
     */
    public void deleteCluster(String name, String callerArn) {
        // Look up cluster record
        GetItemResponse clusterResp = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(clustersTable)
            .key(Map.of("clusterName", AttributeValue.fromS(name)))
            .build());
        if (!clusterResp.hasItem() || clusterResp.item().isEmpty())
            throw new IllegalArgumentException("Cluster not found: " + name);

        Map<String, AttributeValue> cluster = clusterResp.item();
        String ownerArn = cluster.containsKey("ownerArn") ? cluster.get("ownerArn").s() : null;
        if (callerArn != null && ownerArn != null && !callerArn.equals(ownerArn))
            throw new SecurityException("Not authorized to delete cluster: " + name);

        String tenantId = cluster.containsKey("tenantId") ? cluster.get("tenantId").s() : null;
        boolean managed = cluster.containsKey("managed") && "true".equals(cluster.get("managed").s());

        if (managed && tenantId != null) {
            // Full teardown via existing deprovision logic
            deprovision(tenantId);
        } else {
            // Self-managed: just remove records
            dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(clustersTable)
                .key(Map.of("clusterName", AttributeValue.fromS(name)))
                .build());
            if (tenantId != null) {
                dynamoDb.deleteItem(DeleteItemRequest.builder()
                    .tableName(tenantsTable)
                    .key(Map.of("tenantId", AttributeValue.fromS(tenantId)))
                    .build());
            }
            LOG.infof("Deregistered self-managed cluster %s", name);
        }
    }

    private TenantItem itemToTenant(Map<String, AttributeValue> item) {
        return new TenantItem(
            s(item, "tenantId"),
            s(item, "clusterName"),
            "true".equals(s(item, "managed")),
            s(item, "idcUserId"),
            s(item, "ownerArn"),
            s(item, "createdAt"),
            s(item, "updatedAt"),
            s(item, "state"),
            s(item, "phase"),
            item.containsKey("progress") ? Integer.parseInt(item.get("progress").n()) : 0,
            s(item, "instanceId"),
            s(item, "publicIp"),
            s(item, "eipAllocationId"),
            s(item, "sshKeySecretArn"),
            s(item, "ec2PricingModel"),
            s(item, "error")
        );
    }

    // -------------------------------------------------------------------------
    // Ownership & Quota
    // -------------------------------------------------------------------------

    @ConfigProperty(name = "eks-dx.quota.max-tenants-per-caller", defaultValue = "1")
    int maxTenantsPerCaller;

    public int getMaxTenantsPerCaller() { return maxTenantsPerCaller; }

    public int countTenantsByOwner(String ownerArn) {
        ScanResponse resp = dynamoDb.scan(ScanRequest.builder()
            .tableName(tenantsTable)
            .filterExpression("ownerArn = :owner AND #s <> :terminated")
            .expressionAttributeNames(Map.of("#s", "state"))
            .expressionAttributeValues(Map.of(
                ":owner", AttributeValue.fromS(ownerArn),
                ":terminated", AttributeValue.fromS("terminated")))
            .select(Select.COUNT)
            .build());
        return resp.count();
    }

    private String s(Map<String, AttributeValue> item, String key) {
        return item.containsKey(key) ? item.get(key).s() : null;
    }
}
