package cloud.plasticity.eksauth.service;

import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class AwsCredentialService {
    
    private static final Logger LOG = Logger.getLogger(AwsCredentialService.class);
    
    private final StsClient stsClient;
    
    @ConfigProperty(name = "aws.sts.session-duration", defaultValue = "PT1H")
    Duration sessionDuration;
    
    @ConfigProperty(name = "aws.account.id")
    Optional<String> accountId;
    
    @ConfigProperty(name = "aws.region")
    Optional<String> region;
    
    @Inject
    public AwsCredentialService(StsClient stsClient) {
        this.stsClient = stsClient;
    }
    
    // For CDI proxy
    protected AwsCredentialService() {
        this.stsClient = null;
    }
    
    public Credentials assumeRole(String roleArn, String sessionName, 
                                   String clusterName, Map<String, String> sessionTags) {
        try {
            List<Tag> tags = buildSessionTags(clusterName, sessionTags);
            
            AssumeRoleRequest.Builder requestBuilder = AssumeRoleRequest.builder()
                .roleArn(roleArn)
                .roleSessionName(sessionName)
                .durationSeconds((int) sessionDuration.toSeconds())
                .tags(tags);
            
            AssumeRoleResponse response = stsClient.assumeRole(requestBuilder.build());
            
            LOG.infof("Successfully assumed role: %s with %d session tags", roleArn, tags.size());
            
            return response.credentials();
            
        } catch (Exception e) {
            LOG.errorf("Failed to assume role %s: %s", roleArn, e.getMessage());
            throw new RuntimeException("Failed to assume role: " + roleArn, e);
        }
    }
    
    private List<Tag> buildSessionTags(String clusterName, Map<String, String> podTags) {
        List<Tag> tags = new ArrayList<>();
        
        // EKS cluster tags
        String clusterArn = buildClusterArn(clusterName);
        tags.add(Tag.builder().key("eks-cluster-arn").value(clusterArn).build());
        tags.add(Tag.builder().key("eks-cluster-name").value(clusterName).build());
        
        // Kubernetes pod tags
        podTags.forEach((key, value) -> {
            if (value != null && !value.isEmpty()) {
                tags.add(Tag.builder().key(key).value(value).build());
            }
        });
        
        return tags;
    }
    
    private String buildClusterArn(String clusterName) {
        String account = accountId.orElse("123456789012");
        String awsRegion = region.orElse("us-east-1");
        return String.format("arn:aws:eks:%s:%s:cluster/%s", awsRegion, account, clusterName);
    }
    
    public String generateSessionName(String namespace, String serviceAccount) {
        return String.format("eks-pod-identity-%s-%s-%s", 
            namespace, serviceAccount, UUID.randomUUID().toString().substring(0, 8));
    }
}
