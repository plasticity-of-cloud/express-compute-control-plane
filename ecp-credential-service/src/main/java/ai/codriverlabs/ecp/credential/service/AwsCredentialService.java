package ai.codriverlabs.ecp.credential.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AwsCredentialService {

    private static final Logger LOG = Logger.getLogger(AwsCredentialService.class);

    @Inject StsClient stsClient;

    @ConfigProperty(name = "aws.sts.session-duration", defaultValue = "PT1H")
    Duration sessionDuration;

    /**
     * Assumes the target role using Workload Identity-compatible session tags.
     * Produces the same STS session as real EKS Workload Identity (same 6 tags, all transitive).
     */
    public Credentials assumeRole(String roleArn, String clusterName,
                                   String namespace, String serviceAccount,
                                   String podName, String podUid) {
        // EKS Workload Identity-compatible session tags (same 6 keys as real EKS)
        List<Tag> tags = List.of(
            Tag.builder().key("eks-cluster-arn").value(buildClusterArn(clusterName)).build(),
            Tag.builder().key("eks-cluster-name").value(clusterName).build(),
            Tag.builder().key("kubernetes-namespace").value(namespace).build(),
            Tag.builder().key("kubernetes-service-account").value(serviceAccount).build(),
            Tag.builder().key("kubernetes-pod-name").value(podName != null ? podName : "").build(),
            Tag.builder().key("kubernetes-pod-uid").value(podUid != null ? podUid : "").build()
        );
        List<String> transitiveTagKeys = tags.stream().map(Tag::key).toList();

        String sourceIdentity = clusterName + "." + namespace + "." + serviceAccount;
        String sessionName = "ecp." + clusterName + "." + namespace + "." + serviceAccount;
        // RoleSessionName max 64 chars
        if (sessionName.length() > 64) sessionName = sessionName.substring(0, 64);

        AssumeRoleResponse response = stsClient.assumeRole(AssumeRoleRequest.builder()
            .roleArn(roleArn)
            .roleSessionName(sessionName)
            .sourceIdentity(sourceIdentity)
            .durationSeconds((int) sessionDuration.toSeconds())
            .tags(tags)
            .transitiveTagKeys(transitiveTagKeys)
            .build());
        LOG.infof("Assumed role %s for %s (sourceIdentity=%s)", roleArn, sessionName, sourceIdentity);
        return response.credentials();
    }

    private String buildClusterArn(String clusterName) {
        // Construct a synthetic cluster ARN for non-EKS clusters
        // Format mirrors EKS: arn:aws:eks:<region>:<account>:cluster/<name>
        // Using ecp as the service namespace to distinguish from real EKS
        return "arn:aws:ecp:" + System.getenv().getOrDefault("AWS_REGION", "us-east-1")
            + ":" + System.getenv().getOrDefault("AWS_ACCOUNT_ID", "000000000000")
            + ":cluster/" + clusterName;
    }
}
