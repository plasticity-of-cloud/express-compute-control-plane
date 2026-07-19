package ai.codriverlabs.ecp.tenant.service;

import ai.codriverlabs.ecp.tenant.TenantNaming;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dlm.DlmClient;
import software.amazon.awssdk.services.dlm.model.CreateLifecyclePolicyRequest;
import software.amazon.awssdk.services.dlm.model.CreateRule;
import software.amazon.awssdk.services.dlm.model.DeleteLifecyclePolicyRequest;
import software.amazon.awssdk.services.dlm.model.GetLifecyclePoliciesRequest;
import software.amazon.awssdk.services.dlm.model.IntervalUnitValues;
import software.amazon.awssdk.services.dlm.model.PolicyDetails;
import software.amazon.awssdk.services.dlm.model.ResourceTypeValues;
import software.amazon.awssdk.services.dlm.model.RetainRule;
import software.amazon.awssdk.services.dlm.model.Schedule;
import software.amazon.awssdk.services.dlm.model.SettablePolicyStateValues;
import software.amazon.awssdk.services.dlm.model.Tag;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DeleteSnapshotRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Snapshot;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.DeleteRoleRequest;
import software.amazon.awssdk.services.iam.model.DetachRolePolicyRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;

import java.util.List;

/**
 * Creates and deletes DLM lifecycle policies for automated daily etcd volume snapshots.
 * Targets EBS volumes tagged with Purpose=etcd and Name={clusterName}-etcd.
 */
@ApplicationScoped
public class TenantDlmService {

    private static final Logger LOG = Logger.getLogger(TenantDlmService.class);
    private static final String DLM_MANAGED_POLICY = "arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRole";

    @Inject IamClient iam;
    @Inject Ec2Client ec2;
    @Inject StsClient sts;
    private final DlmClient dlm = DlmClient.create();

    public String createEtcdBackupPolicy(String tenantId, String clusterName, String region) {
        String roleName = TenantNaming.dlmRoleName(tenantId);
        try {
            iam.createRole(CreateRoleRequest.builder()
                .roleName(roleName)
                .assumeRolePolicyDocument("""
                    {
                      "Version": "2012-10-17",
                      "Statement": [{
                        "Effect": "Allow",
                        "Principal": { "Service": "dlm.amazonaws.com" },
                        "Action": "sts:AssumeRole"
                      }]
                    }
                    """)
                .build());
        } catch (software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException e) {
            LOG.infof("DLM role %s already exists, reusing", roleName);
        }
        iam.attachRolePolicy(AttachRolePolicyRequest.builder()
            .roleName(roleName)
            .policyArn(DLM_MANAGED_POLICY)
            .build());

        String accountId = sts.getCallerIdentity(GetCallerIdentityRequest.builder().build()).account();
        String roleArn = "arn:aws:iam::" + accountId + ":role/" + roleName;

        String policyId = dlm.createLifecyclePolicy(CreateLifecyclePolicyRequest.builder()
            .description("Daily etcd volume snapshot for " + clusterName)
            .executionRoleArn(roleArn)
            .state(SettablePolicyStateValues.ENABLED)
            .tags(java.util.Map.of(
                "ecp-tenant", tenantId,
                "ecp-cluster", clusterName,
                "Platform", "express-compute"))
            .policyDetails(PolicyDetails.builder()
                .resourceTypes(ResourceTypeValues.VOLUME)
                .targetTags(List.of(
                    Tag.builder().key("Purpose").value("etcd").build(),
                    Tag.builder().key("Name").value(clusterName + "-etcd").build()))
                .schedules(List.of(Schedule.builder()
                    .name("daily-etcd-snapshot")
                    .createRule(CreateRule.builder()
                        .interval(24)
                        .intervalUnit(IntervalUnitValues.HOURS)
                        .times("03:00")
                        .build())
                    .retainRule(RetainRule.builder().count(3).build())
                    .tagsToAdd(List.of(
                        Tag.builder().key("SnapshotCreator").value("DLM").build(),
                        Tag.builder().key("ecp-tenant").value(tenantId).build(),
                        Tag.builder().key("Platform").value("express-compute").build()))
                    .build()))
                .build())
            .build()).policyId();

        LOG.infof("Created DLM policy %s for tenant %s (etcd daily backup, retain 3)", policyId, tenantId);
        return policyId;
    }

    /**
     * Delete DLM policy, associated snapshots, and execution role.
     * Order: snapshots → policy → IAM role.
     */
    public void deleteEtcdBackupPolicy(String tenantId, String clusterName) {
        // 1. Delete snapshots created by this policy
        deleteSnapshots(tenantId);

        // 2. Delete the DLM policy (lookup by tag)
        try {
            var policies = dlm.getLifecyclePolicies(GetLifecyclePoliciesRequest.builder()
                .tagsToAdd(List.of("ecp-tenant=" + tenantId))
                .build()).policies();
            for (var summary : policies) {
                dlm.deleteLifecyclePolicy(DeleteLifecyclePolicyRequest.builder()
                    .policyId(summary.policyId()).build());
                LOG.infof("Deleted DLM policy %s for tenant %s", summary.policyId(), tenantId);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to delete DLM policy for tenant %s: %s", tenantId, e.getMessage());
        }

        // 3. Delete the DLM execution IAM role
        String roleName = TenantNaming.dlmRoleName(tenantId);
        try {
            iam.detachRolePolicy(DetachRolePolicyRequest.builder()
                .roleName(roleName).policyArn(DLM_MANAGED_POLICY).build());
            iam.deleteRole(DeleteRoleRequest.builder().roleName(roleName).build());
            LOG.infof("Deleted DLM role %s", roleName);
        } catch (Exception e) {
            LOG.warnf("Failed to delete DLM role %s: %s", roleName, e.getMessage());
        }
    }

    private void deleteSnapshots(String tenantId) {
        try {
            var snapshots = ec2.describeSnapshots(DescribeSnapshotsRequest.builder()
                .ownerIds("self")
                .filters(
                    Filter.builder().name("tag:ecp-tenant").values(tenantId).build(),
                    Filter.builder().name("tag:Platform").values("express-compute").build())
                .build()).snapshots();
            for (Snapshot snap : snapshots) {
                ec2.deleteSnapshot(DeleteSnapshotRequest.builder().snapshotId(snap.snapshotId()).build());
                LOG.infof("Deleted snapshot %s for tenant %s", snap.snapshotId(), tenantId);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to delete snapshots for tenant %s: %s", tenantId, e.getMessage());
        }
    }
}
