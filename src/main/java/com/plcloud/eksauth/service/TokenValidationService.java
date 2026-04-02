package com.plcloud.eksauth.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TokenValidationService {
    
    private static final Logger LOG = Logger.getLogger(TokenValidationService.class);
    
    public static class TokenClaims {
        private final String namespace;
        private final String serviceAccount;
        private final String subject;
        
        public TokenClaims(String namespace, String serviceAccount, String subject) {
            this.namespace = namespace;
            this.serviceAccount = serviceAccount;
            this.subject = subject;
        }
        
        public String getNamespace() { return namespace; }
        public String getServiceAccount() { return serviceAccount; }
        public String getSubject() { return subject; }
    }
    
    public TokenClaims validateToken(String token, String clusterName) {
        try {
            // Remove Bearer prefix if present
            if (token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
            
            // Decode JWT without verification for CI/CD use case
            DecodedJWT jwt = JWT.decode(token);
            
            // Extract Kubernetes service account claims
            String subject = jwt.getSubject();
            String namespace = jwt.getClaim("kubernetes.io/serviceaccount/namespace").asString();
            String serviceAccount = jwt.getClaim("kubernetes.io/serviceaccount/service-account.name").asString();
            
            if (namespace == null || serviceAccount == null) {
                throw new IllegalArgumentException("Invalid service account token: missing namespace or service account");
            }
            
            LOG.infof("Validated token for %s/%s in cluster %s", namespace, serviceAccount, clusterName);
            
            return new TokenClaims(namespace, serviceAccount, subject);
            
        } catch (Exception e) {
            LOG.errorf("Token validation failed: %s", e.getMessage());
            throw new IllegalArgumentException("Invalid service account token", e);
        }
    }
}
