package ai.codriverlabs.eksdx.tenant.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dlm.DlmClient;
import software.amazon.awssdk.services.dlm.model.CreateLifecyclePolicyRequest;
import software.amazon.awssdk.services.dlm.model.CreateRule;
import software.amazon.awssdk.services.dlm.model.IntervalUnitValues;
import software.amazon.awssdk.services.dlm.model.PolicyDetails;
import software.amazon.awssdk.services.dlm.model.ResourceTypeValues;
import software.amazon.awssdk.services.dlm.model.RetainRule;
import software.amazon.awssdk.services.dlm.model.Schedule;
import software.amazon.awssdk.services.dlm.model.SettablePolicyStateValues;
import software.amazon.awssdk.services.dlm.model.Tag;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;

import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;

/**
 * Creates a DLM lifecycle policy for automated daily etcd volume snapshots.
 * Targets EBS volumes tagged with Purpose=etcd and Name={clusterName}-etcd.
 */
@ApplicationScoped
public class TenantDlmService {

    private static final Logger LOG = Logger.getLogger(TenantDlmService.class);

    @Inject IamClient iam;
    private final DlmClient dlm = DlmClient.create();

    public String createEtcdBackupPolicy(String tenantId, String clusterName, String region) {
        // DLM execution role
        String roleName = "eks-dx-t-" + tenantId + "-dlm";
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
            .policyArn("arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRole")
            .build());

        // Wait briefly for role propagation
        String roleArn = "arn:aws:iam::" + getAccountId() + ":role/" + roleName;

        String policyId = dlm.createLifecyclePolicy(CreateLifecyclePolicyRequest.builder()
            .description("Daily etcd volume snapshot for " + clusterName)
            .executionRoleArn(roleArn)
            .state(SettablePolicyStateValues.ENABLED)
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
                        Tag.builder().key("Cluster").value(clusterName).build(),
                        Tag.builder().key("Platform").value("eks-dx").build()))
                    .build()))
                .build())
            .build()).policyId();

        LOG.infof("Created DLM policy %s for tenant %s (etcd daily backup, retain 3)", policyId, tenantId);
        return policyId;
    }

    private String getAccountId() {
        // Extract from IAM role ARN via GetUser or caller identity
        return software.amazon.awssdk.services.sts.StsClient.create()
            .getCallerIdentity(software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest.builder().build())
            .account();
    }

    /**
     * Best-effort cleanup of DLM policy for a tenant.
     */
    public void deleteEtcdBackupPolicy(String tenantId, String clusterName) {
        var policies = dlm.getLifecyclePolicies(
            software.amazon.awssdk.services.dlm.model.GetLifecyclePoliciesRequest.builder()
                .tagsToAdd("Tenant=" + tenantId)
                .build()).policies();
        for (var summary : policies) {
            dlm.deleteLifecyclePolicy(
                software.amazon.awssdk.services.dlm.model.DeleteLifecyclePolicyRequest.builder()
                    .policyId(summary.policyId()).build());
            LOG.infof("Deleted DLM policy %s for tenant %s", summary.policyId(), tenantId);
        }
    }
}
