package ai.codriverlabs.eksdx.credential.service;

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

    public Credentials assumeRole(String roleArn, String sessionName,
                                   String clusterName, Map<String, String> sessionTags) {
        List<Tag> tags = new ArrayList<>();
        tags.add(Tag.builder().key("eks-cluster-name").value(clusterName).build());
        sessionTags.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) tags.add(Tag.builder().key(k).value(v).build());
        });
        AssumeRoleResponse response = stsClient.assumeRole(AssumeRoleRequest.builder()
            .roleArn(roleArn)
            .roleSessionName(sessionName)
            .durationSeconds((int) sessionDuration.toSeconds())
            .tags(tags)
            .build());
        LOG.infof("Assumed role %s with %d session tags", roleArn, tags.size());
        return response.credentials();
    }
}
