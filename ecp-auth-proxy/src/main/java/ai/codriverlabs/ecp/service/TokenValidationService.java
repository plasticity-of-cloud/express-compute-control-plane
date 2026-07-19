package ai.codriverlabs.ecp.service;

import io.fabric8.kubernetes.api.model.authentication.TokenReview;
import io.fabric8.kubernetes.api.model.authentication.TokenReviewBuilder;
import io.fabric8.kubernetes.api.model.authentication.TokenReviewStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class TokenValidationService {

    private static final Logger LOG = Logger.getLogger(TokenValidationService.class);

    public static final String EKS_POD_IDENTITY_AUDIENCE = "pods.eks.amazonaws.com";

    public static class TokenClaims {
        private final String namespace;
        private final String serviceAccount;
        private final String serviceAccountUid;
        private final String podName;
        private final String podUid;
        private final String subject;
        private final Instant expiration;

        public TokenClaims(String namespace, String serviceAccount, String serviceAccountUid,
                           String podName, String podUid, String subject, Instant expiration) {
            this.namespace = namespace;
            this.serviceAccount = serviceAccount;
            this.serviceAccountUid = serviceAccountUid;
            this.podName = podName;
            this.podUid = podUid;
            this.subject = subject;
            this.expiration = expiration;
        }

        public String getNamespace() { return namespace; }
        public String getServiceAccount() { return serviceAccount; }
        public String getServiceAccountUid() { return serviceAccountUid; }
        public String getPodName() { return podName; }
        public String getPodUid() { return podUid; }
        public String getSubject() { return subject; }
        public Instant getExpiration() { return expiration; }

        public Map<String, String> getSessionTags() {
            return Map.of(
                "kubernetes-namespace", namespace,
                "kubernetes-service-account", serviceAccount,
                "kubernetes-pod-name", podName != null ? podName : "",
                "kubernetes-pod-uid", podUid != null ? podUid : ""
            );
        }
    }

    @Inject
    KubernetesClient kubernetesClient;

    // For testing
    void setKubernetesClient(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    public TokenClaims validateToken(String token, String clusterName) {
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        TokenReview review;
        try {
            review = kubernetesClient.tokenReviews().create(
                new TokenReviewBuilder()
                    .withNewSpec()
                        .withToken(token)
                        .withAudiences(List.of(EKS_POD_IDENTITY_AUDIENCE))
                    .endSpec()
                    .build()
            );
        } catch (Exception e) {
            LOG.warnf("TokenReview API call failed for cluster %s: %s", clusterName, e.getMessage());
            throw new IllegalArgumentException("Token validation failed: " + e.getMessage(), e);
        }

        TokenReviewStatus status = review.getStatus();

        if (status == null || !Boolean.TRUE.equals(status.getAuthenticated())) {
            String reason = status != null ? status.getError() : "no status returned";
            LOG.warnf("TokenReview rejected for cluster %s: %s", clusterName, reason);
            throw new IllegalArgumentException("Invalid service account token: " + reason);
        }

        // status.getUser().getUsername() = "system:serviceaccount:<namespace>:<sa>"
        String username = status.getUser().getUsername();
        String[] parts = username.split(":");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Unexpected token subject format: " + username);
        }

        String namespace = parts[2];
        String serviceAccount = parts[3];
        String serviceAccountUid = status.getUser().getUid();

        // Extra info map may carry pod name/uid depending on Kubernetes version
        Map<String, java.util.List<String>> extra = status.getUser().getExtra();
        String podName = getExtra(extra, "authentication.kubernetes.io/pod-name");
        String podUid  = getExtra(extra, "authentication.kubernetes.io/pod-uid");

        LOG.infof("TokenReview validated for %s/%s in cluster %s (pod: %s)",
            namespace, serviceAccount, clusterName, podName);

        return new TokenClaims(namespace, serviceAccount, serviceAccountUid,
            podName, podUid, username, null);
    }

    private String getExtra(Map<String, java.util.List<String>> extra, String key) {
        if (extra == null) return null;
        var val = extra.get(key);
        return (val != null && !val.isEmpty()) ? val.get(0) : null;
    }
}
