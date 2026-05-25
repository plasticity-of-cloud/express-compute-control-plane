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

import java.security.KeyPair;
import java.security.KeyPairGenerator;
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
    @Inject TenantNetworkService networkService;

    // EC2, SecretsManager, SQS — create via default credential chain
    private final Ec2Client ec2 = Ec2Client.create();
    private final SecretsManagerClient secretsManager = SecretsManagerClient.create();
    private final SqsClient sqs = SqsClient.create();
    private final CloudWatchEventsClient events = CloudWatchEventsClient.create();

    @ConfigProperty(name = "eks-dx.tenants-table")
    String tenantsTable;

    @ConfigProperty(name = "eks-dx.clusters-table")
    String clustersTable;

    @ConfigProperty(name = "eks-dx.tenant.lt-arm64-ondemand")
    String ltArm64Ondemand;

    @ConfigProperty(name = "eks-dx.tenant.lt-arm64-spot")
    String ltArm64Spot;

    @ConfigProperty(name = "eks-dx.tenant.lt-x86-ondemand")
    String ltX86Ondemand;

    @ConfigProperty(name = "eks-dx.tenant.lt-x86-spot")
    String ltX86Spot;

    @ConfigProperty(name = "eks-dx.tenant.subnet-ids")
    String subnetIds;

    @ConfigProperty(name = "eks-dx.tenant.vpc-id")
    String vpcId;

    @ConfigProperty(name = "eks-dx.tenant.availability-zone", defaultValue = "")
    String availabilityZone;

    // -------------------------------------------------------------------------
    // Provision
    // -------------------------------------------------------------------------

    public String provision(String tenantId, String arch, String ec2PricingModel, String k8sVersion) {
        LOG.infof("Provisioning tenant: %s (arch=%s, pricing=%s, k8s=%s)", tenantId, arch, ec2PricingModel, k8sVersion);

        String launchTemplateId = resolveLaunchTemplate(arch, ec2PricingModel);
        String region = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        String accountId = sts.getCallerIdentity(GetCallerIdentityRequest.builder().build()).account();
        String clusterName = "eks-dx-" + tenantId;

        // Create per-tenant network isolation (subnets + security group)
        String az = availabilityZone.isEmpty() ? region + "a" : availabilityZone;
        TenantNetworkService.NetworkResult network = networkService.createTenantNetwork(
            tenantId, clusterName, vpcId, az);
        String subnetId = network.publicSubnetId();

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

        // 3. IAM role with managed policies + inline policies (parity with Terraform)
        String roleName = "eks-dx-tenant-" + tenantId + "-instance-role";
        iam.createRole(CreateRoleRequest.builder()
            .roleName(roleName)
            .assumeRolePolicyDocument(ec2TrustPolicy())
            .tags(software.amazon.awssdk.services.iam.model.Tag.builder()
                .key("eks-cluster-name").value(clusterName).build())
            .build());

        // Managed policies
        for (String policyArn : List.of(
                "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore",
                "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPullOnly",
                "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy",
                "arn:aws:iam::aws:policy/AmazonEBSCSIDriverEKSClusterScopedPolicy",
                "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy")) {
            iam.attachRolePolicy(AttachRolePolicyRequest.builder()
                .roleName(roleName).policyArn(policyArn).build());
        }

        // Inline policy (Karpenter + cloud-provider + secrets + API)
        iam.putRolePolicy(PutRolePolicyRequest.builder()
            .roleName(roleName)
            .policyName("eks-dx-tenant-policy")
            .policyDocument(tenantInstancePolicy(tenantId, region, accountId))
            .build());

        // Instance profile
        iam.createInstanceProfile(CreateInstanceProfileRequest.builder()
            .instanceProfileName(roleName).build());
        iam.addRoleToInstanceProfile(AddRoleToInstanceProfileRequest.builder()
            .instanceProfileName(roleName).roleName(roleName).build());
        LOG.infof("Created IAM role + instance profile %s for tenant %s", roleName, tenantId);

        // 4. SQS queue for Karpenter interruption handling
        String queueUrl = sqs.createQueue(software.amazon.awssdk.services.sqs.model.CreateQueueRequest.builder()
            .queueName(clusterName)
            .attributes(Map.of(
                software.amazon.awssdk.services.sqs.model.QueueAttributeName.MESSAGE_RETENTION_PERIOD, "300"))
            .build()).queueUrl();
        LOG.infof("Created SQS queue %s for tenant %s", clusterName, tenantId);

        // 5. EventBridge rules → SQS (Karpenter spot interruption handling)
        String queueArn = "arn:aws:sqs:" + region + ":" + accountId + ":" + clusterName;
        createEventBridgeRule(clusterName + "-spot-interruption",
            "{\"source\":[\"aws.ec2\"],\"detail-type\":[\"EC2 Spot Instance Interruption Warning\"]}",
            queueArn);
        createEventBridgeRule(clusterName + "-instance-state-change",
            "{\"source\":[\"aws.ec2\"],\"detail-type\":[\"EC2 Instance State-change Notification\"]}",
            queueArn);
        createEventBridgeRule(clusterName + "-instance-rebalance",
            "{\"source\":[\"aws.ec2\"],\"detail-type\":[\"EC2 Instance Rebalance Recommendation\"]}",
            queueArn);
        LOG.infof("Created EventBridge rules for tenant %s", tenantId);

        // 6. Launch EC2 instance
        String eksDxEndpoint = System.getenv().getOrDefault("EKS_DX_ENDPOINT", "https://eks-dx.codriverlabs.ai");
        String userData = Base64.getEncoder().encodeToString(("""
            #!/bin/bash
            mkdir -p /opt/eks-d
            cat > /opt/eks-d/cluster.env <<CONF
            TENANT_ID="%s"
            CLUSTER_NAME="%s"
            EKS_DX_ENDPOINT="%s"
            EKS_DX_API_URL="%s/clusters/%s/assets"
            REGION="%s"
            K8S_VERSION="%s"
            CONF
            """.formatted(tenantId, clusterName, eksDxEndpoint, eksDxEndpoint, clusterName, region, k8sVersion)).getBytes());

        RunInstancesResponse runResp = ec2.runInstances(RunInstancesRequest.builder()
            .launchTemplate(LaunchTemplateSpecification.builder()
                .launchTemplateId(launchTemplateId).build())
            .subnetId(subnetId)
            .securityGroupIds(network.securityGroupId())
            .keyName("eks-dx-tenant-" + tenantId)
            .iamInstanceProfile(IamInstanceProfileSpecification.builder()
                .name(roleName).build())
            .userData(userData)
            .minCount(1).maxCount(1)
            .tagSpecifications(TagSpecification.builder()
                .resourceType(ResourceType.INSTANCE)
                .tags(
                    Tag.builder().key("Name").value(clusterName).build(),
                    Tag.builder().key("eks-dx-tenant").value(tenantId).build(),
                    Tag.builder().key("kubernetes.io/cluster/" + clusterName).value("owned").build(),
                    Tag.builder().key("ebs.csi.aws.com/cluster-name").value(clusterName).build(),
                    Tag.builder().key("Platform").value("eks-d-xpress").build())
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

    private String resolveLaunchTemplate(String arch, String pricingModel) {
        return switch (arch + "/" + pricingModel) {
            case "arm64/ondemand" -> ltArm64Ondemand;
            case "arm64/spot" -> ltArm64Spot;
            case "x86_64/ondemand" -> ltX86Ondemand;
            case "x86_64/spot" -> ltX86Spot;
            default -> throw new IllegalArgumentException("Invalid arch/pricing: " + arch + "/" + pricingModel);
        };
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
        String clusterName = "eks-dx-" + tenantId;
        return """
            {
              "Version": "2012-10-17",
              "Statement": [
                {
                  "Sid": "SecretsAccess",
                  "Effect": "Allow",
                  "Action": "secretsmanager:GetSecretValue",
                  "Resource": "arn:aws:secretsmanager:%s:%s:secret:eks-dx/tenant/%s/*"
                },
                {
                  "Sid": "EksDxApiInvoke",
                  "Effect": "Allow",
                  "Action": "execute-api:Invoke",
                  "Resource": "arn:aws:execute-api:%s:%s:*/*/POST/clusters/%s"
                },
                {
                  "Sid": "TenantStateUpdate",
                  "Effect": "Allow",
                  "Action": "dynamodb:UpdateItem",
                  "Resource": "arn:aws:dynamodb:%s:%s:table/eks-dx-tenants",
                  "Condition": {
                    "ForAllValues:StringEquals": { "dynamodb:LeadingKeys": ["%s"] }
                  }
                },
                {
                  "Sid": "SSMCore",
                  "Effect": "Allow",
                  "Action": [
                    "ssm:DescribeAssociation", "ssm:GetDeployablePatchSnapshotForInstance",
                    "ssm:GetDocument", "ssm:DescribeDocument", "ssm:GetManifest",
                    "ssm:GetParameter", "ssm:GetParameters", "ssm:ListAssociations",
                    "ssm:ListInstanceAssociations", "ssm:PutInventory", "ssm:PutComplianceItems",
                    "ssm:PutConfigurePackageResult", "ssm:UpdateAssociationStatus",
                    "ssm:UpdateInstanceAssociationStatus", "ssm:UpdateInstanceInformation",
                    "ssmmessages:*", "ec2messages:*"
                  ],
                  "Resource": "*"
                },
                {
                  "Sid": "ECRPull",
                  "Effect": "Allow",
                  "Action": [
                    "ecr:GetAuthorizationToken", "ecr:BatchCheckLayerAvailability",
                    "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"
                  ],
                  "Resource": "*"
                },
                {
                  "Sid": "CloudWatch",
                  "Effect": "Allow",
                  "Action": [
                    "cloudwatch:PutMetricData", "logs:CreateLogGroup", "logs:CreateLogStream",
                    "logs:PutLogEvents", "logs:DescribeLogStreams"
                  ],
                  "Resource": "*"
                },
                {
                  "Sid": "EKSCNI",
                  "Effect": "Allow",
                  "Action": [
                    "ec2:AssignPrivateIpAddresses", "ec2:AttachNetworkInterface",
                    "ec2:CreateNetworkInterface", "ec2:DeleteNetworkInterface",
                    "ec2:DescribeNetworkInterfaces", "ec2:DetachNetworkInterface",
                    "ec2:ModifyNetworkInterfaceAttribute", "ec2:UnassignPrivateIpAddresses"
                  ],
                  "Resource": "*"
                },
                {
                  "Sid": "EBSCSI",
                  "Effect": "Allow",
                  "Action": [
                    "ec2:CreateVolume", "ec2:DeleteVolume", "ec2:AttachVolume",
                    "ec2:DetachVolume", "ec2:ModifyVolume", "ec2:DescribeVolumes",
                    "ec2:DescribeVolumeStatus", "ec2:CreateSnapshot", "ec2:DeleteSnapshot",
                    "ec2:DescribeSnapshots", "ec2:CreateTags"
                  ],
                  "Resource": "*",
                  "Condition": {
                    "StringEquals": {
                      "aws:RequestTag/ebs.csi.aws.com/cluster-name": "%s"
                    }
                  }
                },
                {
                  "Sid": "KarpenterRead",
                  "Effect": "Allow",
                  "Action": [
                    "ec2:DescribeAvailabilityZones", "ec2:DescribeImages",
                    "ec2:DescribeInstances", "ec2:DescribeInstanceTypes",
                    "ec2:DescribeInstanceTypeOfferings", "ec2:DescribeLaunchTemplates",
                    "ec2:DescribeSecurityGroups", "ec2:DescribeSpotPriceHistory",
                    "ec2:DescribeSubnets", "ec2:DescribeVolumes", "ec2:DescribeVpcs",
                    "pricing:GetProducts", "ssm:GetParameter",
                    "iam:ListInstanceProfiles", "iam:GetInstanceProfile",
                    "iam:CreateInstanceProfile", "iam:DeleteInstanceProfile",
                    "iam:AddRoleToInstanceProfile", "iam:RemoveRoleFromInstanceProfile",
                    "iam:TagInstanceProfile"
                  ],
                  "Resource": "*"
                },
                {
                  "Sid": "KarpenterWrite",
                  "Effect": "Allow",
                  "Action": [
                    "ec2:RunInstances", "ec2:CreateFleet", "ec2:CreateLaunchTemplate",
                    "ec2:DeleteLaunchTemplate", "ec2:TerminateInstances", "ec2:CreateTags"
                  ],
                  "Resource": "*",
                  "Condition": {
                    "StringEquals": {
                      "aws:RequestTag/kubernetes.io/cluster/%s": "owned"
                    }
                  }
                },
                {
                  "Sid": "KarpenterDelete",
                  "Effect": "Allow",
                  "Action": ["ec2:TerminateInstances", "ec2:DeleteLaunchTemplate"],
                  "Resource": "*",
                  "Condition": {
                    "StringEquals": {
                      "ec2:ResourceTag/kubernetes.io/cluster/%s": "owned"
                    }
                  }
                },
                {
                  "Sid": "KarpenterPassRole",
                  "Effect": "Allow",
                  "Action": "iam:PassRole",
                  "Resource": "arn:aws:iam::%s:role/eks-dx-tenant-%s-instance-role"
                },
                {
                  "Sid": "KarpenterSQS",
                  "Effect": "Allow",
                  "Action": ["sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:GetQueueUrl", "sqs:ReceiveMessage"],
                  "Resource": "arn:aws:sqs:%s:%s:%s"
                },
                {
                  "Sid": "CloudProviderRead",
                  "Effect": "Allow",
                  "Action": [
                    "ec2:DescribeInstances", "ec2:DescribeRegions", "ec2:DescribeRouteTables",
                    "ec2:DescribeSecurityGroups", "ec2:DescribeSubnets", "ec2:DescribeVolumes",
                    "ec2:DescribeVpcs", "ec2:DescribeAvailabilityZones",
                    "elasticloadbalancing:DescribeLoadBalancers",
                    "elasticloadbalancing:DescribeLoadBalancerAttributes",
                    "elasticloadbalancing:DescribeListeners",
                    "elasticloadbalancing:DescribeTargetGroups",
                    "elasticloadbalancing:DescribeTargetHealth",
                    "iam:CreateServiceLinkedRole", "kms:DescribeKey"
                  ],
                  "Resource": "*"
                },
                {
                  "Sid": "CloudProviderWriteTagged",
                  "Effect": "Allow",
                  "Action": [
                    "ec2:CreateSecurityGroup", "ec2:CreateRoute", "ec2:CreateTags",
                    "ec2:ModifyInstanceAttribute", "ec2:AttachVolume", "ec2:DetachVolume",
                    "ec2:DeleteVolume", "ec2:AuthorizeSecurityGroupIngress",
                    "ec2:RevokeSecurityGroupIngress", "ec2:DeleteSecurityGroup", "ec2:DeleteRoute",
                    "elasticloadbalancing:AddTags", "elasticloadbalancing:CreateLoadBalancer",
                    "elasticloadbalancing:CreateListener", "elasticloadbalancing:CreateTargetGroup",
                    "elasticloadbalancing:DeleteLoadBalancer", "elasticloadbalancing:DeleteListener",
                    "elasticloadbalancing:DeleteTargetGroup", "elasticloadbalancing:ModifyListener",
                    "elasticloadbalancing:ModifyTargetGroup", "elasticloadbalancing:RegisterTargets",
                    "elasticloadbalancing:ConfigureHealthCheck",
                    "elasticloadbalancing:RegisterInstancesWithLoadBalancer",
                    "elasticloadbalancing:DeregisterInstancesFromLoadBalancer"
                  ],
                  "Resource": "*",
                  "Condition": {
                    "StringEquals": {
                      "aws:ResourceTag/kubernetes.io/cluster/%s": "owned"
                    }
                  }
                }
              ]
            }
            """.formatted(
                region, accountId, tenantId,       // SecretsAccess
                region, accountId, tenantId,       // EksDxApiInvoke
                region, accountId, tenantId,       // TenantStateUpdate
                clusterName,                       // EBSCSI
                clusterName,                       // KarpenterWrite
                clusterName,                       // KarpenterDelete
                accountId, tenantId,               // KarpenterPassRole
                region, accountId, clusterName,    // KarpenterSQS
                clusterName                        // CloudProviderWriteTagged
            );
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
