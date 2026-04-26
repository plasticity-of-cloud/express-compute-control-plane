package cloud.plasticity.eksauth.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public class AssumeRoleForPodIdentityResponse {
    
    @JsonProperty("Credentials")
    private Credentials credentials;
    
    @JsonProperty("AssumedRoleUser")
    private AssumedRoleUser assumedRoleUser;
    
    @JsonProperty("PodIdentityAssociation")
    private PodIdentityAssociation podIdentityAssociation;
    
    @JsonProperty("Subject")
    private Subject subject;
    
    @JsonProperty("Audience")
    private String audience;
    
    public AssumeRoleForPodIdentityResponse() {}
    
    public static class Credentials {
        @JsonProperty("AccessKeyId")
        private String accessKeyId;
        
        @JsonProperty("SecretAccessKey")
        private String secretAccessKey;
        
        @JsonProperty("SessionToken")
        private String sessionToken;
        
        @JsonProperty("Expiration")
        private Instant expiration;
        
        // Getters and setters
        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
        
        public String getSecretAccessKey() { return secretAccessKey; }
        public void setSecretAccessKey(String secretAccessKey) { this.secretAccessKey = secretAccessKey; }
        
        public String getSessionToken() { return sessionToken; }
        public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }
        
        public Instant getExpiration() { return expiration; }
        public void setExpiration(Instant expiration) { this.expiration = expiration; }
    }
    
    public static class AssumedRoleUser {
        @JsonProperty("Arn")
        private String arn;
        
        @JsonProperty("AssumeRoleId")
        private String assumeRoleId;
        
        public String getArn() { return arn; }
        public void setArn(String arn) { this.arn = arn; }
        
        public String getAssumeRoleId() { return assumeRoleId; }
        public void setAssumeRoleId(String assumeRoleId) { this.assumeRoleId = assumeRoleId; }
    }
    
    public static class PodIdentityAssociation {
        @JsonProperty("Arn")
        private String arn;
        
        @JsonProperty("AssociationId")
        private String associationId;
        
        public String getArn() { return arn; }
        public void setArn(String arn) { this.arn = arn; }
        
        public String getAssociationId() { return associationId; }
        public void setAssociationId(String associationId) { this.associationId = associationId; }
    }
    
    public static class Subject {
        @JsonProperty("Namespace")
        private String namespace;
        
        @JsonProperty("ServiceAccount")
        private String serviceAccount;
        
        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
        
        public String getServiceAccount() { return serviceAccount; }
        public void setServiceAccount(String serviceAccount) { this.serviceAccount = serviceAccount; }
    }
    
    // Main getters and setters
    public Credentials getCredentials() { return credentials; }
    public void setCredentials(Credentials credentials) { this.credentials = credentials; }
    
    public AssumedRoleUser getAssumedRoleUser() { return assumedRoleUser; }
    public void setAssumedRoleUser(AssumedRoleUser assumedRoleUser) { this.assumedRoleUser = assumedRoleUser; }
    
    public PodIdentityAssociation getPodIdentityAssociation() { return podIdentityAssociation; }
    public void setPodIdentityAssociation(PodIdentityAssociation podIdentityAssociation) { this.podIdentityAssociation = podIdentityAssociation; }
    
    public Subject getSubject() { return subject; }
    public void setSubject(Subject subject) { this.subject = subject; }
    
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
}
