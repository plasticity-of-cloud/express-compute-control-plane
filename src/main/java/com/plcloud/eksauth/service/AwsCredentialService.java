package com.plcloud.eksauth.service;

import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.UUID;

@ApplicationScoped
public class AwsCredentialService {
    
    private static final Logger LOG = Logger.getLogger(AwsCredentialService.class);
    
    private final StsClient stsClient;
    
    @ConfigProperty(name = "aws.sts.session-duration", defaultValue = "PT1H")
    Duration sessionDuration;
    
    public AwsCredentialService() {
        this.stsClient = StsClient.builder().build();
    }
    
    public Credentials assumeRole(String roleArn, String sessionName) {
        try {
            AssumeRoleRequest request = AssumeRoleRequest.builder()
                .roleArn(roleArn)
                .roleSessionName(sessionName)
                .durationSeconds((int) sessionDuration.toSeconds())
                .build();
            
            AssumeRoleResponse response = stsClient.assumeRole(request);
            
            LOG.infof("Successfully assumed role: %s", roleArn);
            
            return response.credentials();
            
        } catch (Exception e) {
            LOG.errorf("Failed to assume role %s: %s", roleArn, e.getMessage());
            throw new RuntimeException("Failed to assume role: " + roleArn, e);
        }
    }
    
    public String generateSessionName(String namespace, String serviceAccount) {
        return String.format("eks-pod-identity-%s-%s-%s", 
            namespace, serviceAccount, UUID.randomUUID().toString().substring(0, 8));
    }
}
