package ai.codriverlabs.eksdx.tenant.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AddRoleToInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.CreateInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest;

import java.util.List;

/**
 * Creates per-tenant IAM role + instance profile with full EKS-D permissions.
 *
 * Managed policies: SSM, ECR, EKS CNI, EBS CSI, CloudWatch.
 * Inline policy: Karpenter, cloud-provider, secrets, API invoke.
 */
@ApplicationScoped
public class TenantIamService {

    private static final Logger LOG = Logger.getLogger(TenantIamService.class);

    private static final List<String> MANAGED_POLICIES = List.of(
        "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore",
        "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPullOnly",
        "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy",
        "arn:aws:iam::aws:policy/AmazonEBSCSIDriverEKSClusterScopedPolicy",
        "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
    );

    @Inject IamClient iam;

    public record IamResult(String roleName, String instanceProfileName) {}

    public IamResult createTenantRole(String tenantId, String clusterName, String region, String accountId) {
        String roleName = "eks-dx-t-" + tenantId + "-ir";

        try {
            iam.createRole(CreateRoleRequest.builder()
                .roleName(roleName)
                .assumeRolePolicyDocument(EC2_TRUST_POLICY)
                .tags(software.amazon.awssdk.services.iam.model.Tag.builder()
                    .key("eks-cluster-name").value(clusterName).build())
                .build());
        } catch (software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException e) {
            LOG.infof("IAM role %s already exists, reusing", roleName);
        }

        for (String policyArn : MANAGED_POLICIES) {
            iam.attachRolePolicy(AttachRolePolicyRequest.builder()
                .roleName(roleName).policyArn(policyArn).build());
        }

        iam.putRolePolicy(PutRolePolicyRequest.builder()
            .roleName(roleName)
            .policyName("eks-dx-tenant-policy")
            .policyDocument(tenantInlinePolicy(tenantId, clusterName, region, accountId))
            .build());

        try {
            iam.createInstanceProfile(CreateInstanceProfileRequest.builder()
                .instanceProfileName(roleName).build());
            iam.addRoleToInstanceProfile(AddRoleToInstanceProfileRequest.builder()
                .instanceProfileName(roleName).roleName(roleName).build());
        } catch (software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException e) {
            LOG.infof("Instance profile %s already exists, reusing", roleName);
        }

        LOG.infof("Created IAM role + instance profile %s for tenant %s", roleName, tenantId);
        return new IamResult(roleName, roleName);
    }

    private static final String EC2_TRUST_POLICY = """
        {
          "Version": "2012-10-17",
          "Statement": [{
            "Effect": "Allow",
            "Principal": { "Service": "ec2.amazonaws.com" },
            "Action": "sts:AssumeRole"
          }]
        }
        """;

    private String tenantInlinePolicy(String tenantId, String clusterName, String region, String accountId) {
        return """
            {
              "Version": "2012-10-17",
              "Statement": [
                {
                  "Sid": "SecretsAccess",
                  "Effect": "Allow",
                  "Action": "secretsmanager:GetSecretValue",
                  "Resource": "arn:aws:secretsmanager:%s:%s:secret:eks-dx/t/%s/*"
                },
                {
                  "Sid": "EksDxApiInvoke",
                  "Effect": "Allow",
                  "Action": "execute-api:Invoke",
                  "Resource": [
                    "arn:aws:execute-api:%s:%s:*/*/GET/clusters",
                    "arn:aws:execute-api:%s:%s:*/*/POST/clusters",
                    "arn:aws:execute-api:%s:%s:*/*/GET/clusters/%s",
                    "arn:aws:execute-api:%s:%s:*/*/POST/clusters/%s",
                    "arn:aws:execute-api:%s:%s:*/*/DELETE/clusters/%s",
                    "arn:aws:execute-api:%s:%s:*/*/GET/clusters/%s/pod-identity-associations",
                    "arn:aws:execute-api:%s:%s:*/*/POST/clusters/%s/pod-identity-associations",
                    "arn:aws:execute-api:%s:%s:*/*/DELETE/clusters/%s/pod-identity-associations/*"
                  ]
                },
                {
                  "Sid": "TenantStateUpdate",
                  "Effect": "Allow",
                  "Action": "dynamodb:UpdateItem",
                  "Resource": "arn:aws:dynamodb:%s:%s:table/eks-d-xpress-tenants",
                  "Condition": {
                    "ForAllValues:StringEquals": { "dynamodb:LeadingKeys": ["%s"] }
                  }
                },
                {
                  "Sid": "SSMAndECRAndCloudWatch",
                  "Effect": "Allow",
                  "Action": [
                    "ssm:GetParameter", "ssm:GetParameters",
                    "ssm:DescribeAssociation", "ssm:UpdateInstanceInformation",
                    "ssmmessages:*", "ec2messages:*",
                    "ecr:GetAuthorizationToken", "ecr:BatchCheckLayerAvailability",
                    "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage",
                    "cloudwatch:PutMetricData", "logs:CreateLogGroup",
                    "logs:CreateLogStream", "logs:PutLogEvents"
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
                  "Sid": "KarpenterResourceDiscovery",
                  "Effect": "Allow",
                  "Action": [
                    "ec2:DescribeAvailabilityZones", "ec2:DescribeCapacityReservations",
                    "ec2:DescribeImages", "ec2:DescribeInstances", "ec2:DescribeInstanceStatus",
                    "ec2:DescribeInstanceTypes", "ec2:DescribeInstanceTypeOfferings",
                    "ec2:DescribeLaunchTemplates", "ec2:DescribeLaunchTemplateVersions",
                    "ec2:DescribePlacementGroups", "ec2:DescribeSecurityGroups",
                    "ec2:DescribeSpotPriceHistory", "ec2:DescribeSubnets",
                    "ec2:DescribeVolumes", "ec2:DescribeVpcs",
                    "pricing:GetProducts", "ssm:GetParameter",
                    "iam:ListInstanceProfiles", "iam:GetInstanceProfile"
                  ],
                  "Resource": "*",
                  "Condition": { "StringEquals": { "aws:RequestedRegion": "%s" } }
                },
                {
                  "Sid": "KarpenterIAMInstanceProfile",
                  "Effect": "Allow",
                  "Action": [
                    "iam:CreateInstanceProfile", "iam:DeleteInstanceProfile",
                    "iam:AddRoleToInstanceProfile", "iam:RemoveRoleFromInstanceProfile",
                    "iam:TagInstanceProfile"
                  ],
                  "Resource": "arn:aws:iam::%s:instance-profile/*",
                  "Condition": {
                    "StringEquals": { "aws:ResourceTag/kubernetes.io/cluster/%s": "owned" },
                    "StringLike": { "aws:ResourceTag/karpenter.k8s.aws/ec2nodeclass": "*" }
                  }
                },
                {
                  "Sid": "KarpenterInstanceAccessActions",
                  "Effect": "Allow",
                  "Action": ["ec2:RunInstances", "ec2:CreateFleet"],
                  "Resource": [
                    "arn:aws:ec2:%s::image/*",
                    "arn:aws:ec2:%s::snapshot/*",
                    "arn:aws:ec2:%s:*:security-group/*",
                    "arn:aws:ec2:%s:*:subnet/*",
                    "arn:aws:ec2:%s:*:capacity-reservation/*",
                    "arn:aws:ec2:%s:*:placement-group/*"
                  ]
                },
                {
                  "Sid": "KarpenterLaunchTemplateAccess",
                  "Effect": "Allow",
                  "Action": ["ec2:RunInstances", "ec2:CreateFleet"],
                  "Resource": "arn:aws:ec2:%s:*:launch-template/*",
                  "Condition": {
                    "StringEquals": { "aws:ResourceTag/kubernetes.io/cluster/%s": "owned" },
                    "StringLike": { "aws:ResourceTag/karpenter.sh/nodepool": "*" }
                  }
                },
                {
                  "Sid": "KarpenterAuthChecks",
                  "Effect": "Allow",
                  "Action": ["ec2:CreateLaunchTemplate", "ec2:CreateTags", "ec2:CreateFleet", "ec2:RunInstances"],
                  "Resource": "*"
                },
                {
                  "Sid": "KarpenterCreateWithTags",
                  "Effect": "Allow",
                  "Action": ["ec2:RunInstances", "ec2:CreateFleet", "ec2:CreateLaunchTemplate"],
                  "Resource": [
                    "arn:aws:ec2:%s:*:fleet/*",
                    "arn:aws:ec2:%s:*:instance/*",
                    "arn:aws:ec2:%s:*:volume/*",
                    "arn:aws:ec2:%s:*:network-interface/*",
                    "arn:aws:ec2:%s:*:launch-template/*",
                    "arn:aws:ec2:%s:*:spot-instances-request/*"
                  ],
                  "Condition": {
                    "StringEquals": { "aws:RequestTag/kubernetes.io/cluster/%s": "owned" },
                    "StringLike": { "aws:RequestTag/karpenter.sh/nodepool": "*" }
                  }
                },
                {
                  "Sid": "KarpenterTagOnCreate",
                  "Effect": "Allow",
                  "Action": "ec2:CreateTags",
                  "Resource": [
                    "arn:aws:ec2:%s:*:fleet/*",
                    "arn:aws:ec2:%s:*:instance/*",
                    "arn:aws:ec2:%s:*:volume/*",
                    "arn:aws:ec2:%s:*:network-interface/*",
                    "arn:aws:ec2:%s:*:launch-template/*",
                    "arn:aws:ec2:%s:*:spot-instances-request/*"
                  ],
                  "Condition": {
                    "StringEquals": {
                      "aws:RequestTag/kubernetes.io/cluster/%s": "owned",
                      "ec2:CreateAction": ["RunInstances", "CreateFleet", "CreateLaunchTemplate"]
                    },
                    "StringLike": { "aws:RequestTag/karpenter.sh/nodepool": "*" }
                  }
                },
                {
                  "Sid": "KarpenterTagInstances",
                  "Effect": "Allow",
                  "Action": "ec2:CreateTags",
                  "Resource": "arn:aws:ec2:%s:*:instance/*",
                  "Condition": {
                    "StringEquals": { "aws:ResourceTag/kubernetes.io/cluster/%s": "owned" },
                    "StringLike": { "aws:ResourceTag/karpenter.sh/nodepool": "*" },
                    "ForAllValues:StringEquals": {
                      "aws:TagKeys": ["eks:eks-cluster-name", "karpenter.sh/nodeclaim", "Name"]
                    }
                  }
                },
                {
                  "Sid": "KarpenterDelete",
                  "Effect": "Allow",
                  "Action": ["ec2:TerminateInstances", "ec2:DeleteLaunchTemplate"],
                  "Resource": [
                    "arn:aws:ec2:%s:*:instance/*",
                    "arn:aws:ec2:%s:*:launch-template/*"
                  ],
                  "Condition": {
                    "StringEquals": { "aws:ResourceTag/kubernetes.io/cluster/%s": "owned" },
                    "StringLike": { "aws:ResourceTag/karpenter.sh/nodepool": "*" }
                  }
                },
                {
                  "Sid": "KarpenterPassRole",
                  "Effect": "Allow",
                  "Action": "iam:PassRole",
                  "Resource": "arn:aws:iam::%s:role/eks-dx-t-%s-ir",
                  "Condition": { "StringEquals": { "iam:PassedToService": "ec2.amazonaws.com" } }
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
                    "elasticloadbalancing:Describe*",
                    "iam:CreateServiceLinkedRole", "kms:DescribeKey"
                  ],
                  "Resource": "*"
                },
                {
                  "Sid": "CloudProviderWriteTagged",
                  "Effect": "Allow",
                  "Action": [
                    "ec2:CreateSecurityGroup", "ec2:CreateRoute", "ec2:CreateTags", "ec2:CreateVolume",
                    "ec2:ModifyInstanceAttribute", "ec2:ModifyVolume", "ec2:AttachVolume", "ec2:DetachVolume",
                    "ec2:DeleteVolume", "ec2:AuthorizeSecurityGroupIngress",
                    "ec2:RevokeSecurityGroupIngress", "ec2:DeleteSecurityGroup", "ec2:DeleteRoute",
                    "elasticloadbalancing:*"
                  ],
                  "Resource": "*",
                  "Condition": { "StringEquals": { "aws:ResourceTag/kubernetes.io/cluster/%s": "owned" } }
                }
              ]
            }
            """.formatted(
                region, accountId, tenantId,       // SecretsAccess
                region, accountId,                 // EksDxApiInvoke GET /clusters
                region, accountId,                 // EksDxApiInvoke POST /clusters
                region, accountId, clusterName,    // EksDxApiInvoke GET /clusters/{name}
                region, accountId, clusterName,    // EksDxApiInvoke POST /clusters/{name}
                region, accountId, clusterName,    // EksDxApiInvoke DELETE /clusters/{name}
                region, accountId, clusterName,    // EksDxApiInvoke GET /clusters/{name}/pod-identity-associations
                region, accountId, clusterName,    // EksDxApiInvoke POST /clusters/{name}/pod-identity-associations
                region, accountId, clusterName,    // EksDxApiInvoke DELETE /clusters/{name}/pod-identity-associations/*
                region, accountId, tenantId,       // TenantStateUpdate
                region,                            // KarpenterResourceDiscovery
                accountId, clusterName,            // KarpenterIAMInstanceProfile
                region, region, region, region, region, region, // KarpenterInstanceAccessActions
                region, clusterName,                        // KarpenterLaunchTemplateAccess
                // KarpenterAuthChecks — Resource: * needs no format args
                region, region, region, region, region, region, clusterName, // KarpenterCreateWithTags
                region, region, region, region, region, region, clusterName, // KarpenterTagOnCreate
                region, clusterName,               // KarpenterTagInstances
                region, region, clusterName,       // KarpenterDelete
                accountId, tenantId,               // KarpenterPassRole
                region, accountId, clusterName,    // KarpenterSQS
                clusterName                        // CloudProviderWriteTagged
            );
    }

    /**
     * Best-effort cleanup of tenant IAM role and instance profile.
     */
    public void deleteTenantRole(String roleName, String instanceProfileName) {
        try {
            iam.removeRoleFromInstanceProfile(software.amazon.awssdk.services.iam.model.RemoveRoleFromInstanceProfileRequest.builder()
                .instanceProfileName(instanceProfileName).roleName(roleName).build());
        } catch (Exception ignored) {}
        try {
            iam.deleteInstanceProfile(software.amazon.awssdk.services.iam.model.DeleteInstanceProfileRequest.builder()
                .instanceProfileName(instanceProfileName).build());
        } catch (Exception ignored) {}
        try {
            iam.listRolePolicies(software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest.builder()
                .roleName(roleName).build()).policyNames().forEach(policy ->
                    iam.deleteRolePolicy(software.amazon.awssdk.services.iam.model.DeleteRolePolicyRequest.builder()
                        .roleName(roleName).policyName(policy).build()));
        } catch (Exception ignored) {}
        try {
            iam.listAttachedRolePolicies(software.amazon.awssdk.services.iam.model.ListAttachedRolePoliciesRequest.builder()
                .roleName(roleName).build()).attachedPolicies().forEach(policy ->
                    iam.detachRolePolicy(software.amazon.awssdk.services.iam.model.DetachRolePolicyRequest.builder()
                        .roleName(roleName).policyArn(policy.policyArn()).build()));
        } catch (Exception ignored) {}
        try {
            iam.deleteRole(software.amazon.awssdk.services.iam.model.DeleteRoleRequest.builder()
                .roleName(roleName).build());
        } catch (Exception ignored) {}
    }
}
