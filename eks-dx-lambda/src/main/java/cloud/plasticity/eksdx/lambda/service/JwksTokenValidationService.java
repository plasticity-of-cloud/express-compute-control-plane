package cloud.plasticity.eksdx.lambda.service;

import cloud.plasticity.eksdx.lambda.model.TokenClaims;
import io.smallrye.jwt.auth.principal.JWTAuthContextInfo;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates Kubernetes SA tokens using JWKS stored in DynamoDB.
 * Uses SmallRye JWT's {@link JWTParser} with per-cluster {@link JWTAuthContextInfo}
 * for JWKS resolution. JWKS is cached in memory with a 5-minute TTL.
 */
@ApplicationScoped
public class JwksTokenValidationService {

    private static final Logger LOG = Logger.getLogger(JwksTokenValidationService.class);
    private static final String EKS_POD_IDENTITY_AUDIENCE = "pods.eks.amazonaws.com";
    private static final String EKS_DX_AUDIENCE = "eks-dx.plasticity.cloud";
    private static final long JWKS_CACHE_TTL_SECONDS = 300; // 5 minutes

    @Inject
    DynamoDbClusterService clusterService;

    @Inject
    JWTParser jwtParser;

    private final Map<String, CachedContext> contextCache = new ConcurrentHashMap<>();

    /**
     * Validate a pod SA token for credential exchange.
     * Audience: pods.eks.amazonaws.com
     */
    public TokenClaims validateToken(String token, String clusterName) {
        JsonWebToken jwt = validateJwt(token, clusterName, EKS_POD_IDENTITY_AUDIENCE);
        return extractTokenClaims(jwt);
    }

    /**
     * Validate a webhook SA token for management API access.
     * Audience: eks-dx.plasticity.cloud
     */
    public void validateWebhookToken(String token, String clusterName, String expectedSubject) {
        JsonWebToken jwt = validateJwt(token, clusterName, EKS_DX_AUDIENCE);
        String subject = jwt.getSubject();
        if (!expectedSubject.equals(subject)) {
            throw new SecurityException("Unexpected subject: " + subject);
        }
    }

    private JsonWebToken validateJwt(String token, String clusterName, String expectedAudience) {
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        JWTAuthContextInfo contextInfo = getContextInfo(clusterName, expectedAudience);

        try {
            return jwtParser.parse(token, contextInfo);
        } catch (ParseException e) {
            LOG.warnf("JWT validation failed for cluster %s: %s", clusterName, e.getMessage());
            throw new IllegalArgumentException("Invalid token: " + e.getMessage(), e);
        }
    }

    private JWTAuthContextInfo getContextInfo(String clusterName, String audience) {
        String cacheKey = clusterName + "|" + audience;
        CachedContext cached = contextCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.contextInfo;
        }

        String jwksJson = clusterService.getJwks(clusterName);
        String issuer = clusterService.getIssuer(clusterName);

        JWTAuthContextInfo contextInfo = new JWTAuthContextInfo();
        contextInfo.setPublicKeyContent(jwksJson);
        contextInfo.setIssuedBy(issuer);
        contextInfo.setExpectedAudience(Set.of(audience));
        contextInfo.setRequireNamedPrincipal(true);

        contextCache.put(cacheKey, new CachedContext(contextInfo, Instant.now()));
        LOG.infof("JWKS context loaded for cluster %s (audience: %s)", clusterName, audience);
        return contextInfo;
    }

    private TokenClaims extractTokenClaims(JsonWebToken jwt) {
        String subject = jwt.getSubject();
        String[] parts = subject.split(":");
        if (parts.length != 4 || !"system".equals(parts[0]) || !"serviceaccount".equals(parts[1])) {
            throw new IllegalArgumentException("Unexpected subject format: " + subject);
        }

        String namespace = parts[2];
        String serviceAccount = parts[3];

        String podName = jwt.getClaim("kubernetes.io/pod/name");
        String podUid = jwt.getClaim("kubernetes.io/pod/uid");
        String saUid = jwt.getClaim("kubernetes.io/serviceaccount/uid");

        LOG.infof("Token validated: %s/%s (pod: %s)", namespace, serviceAccount, podName);
        return new TokenClaims(namespace, serviceAccount, saUid, podName, podUid, subject);
    }

    private record CachedContext(JWTAuthContextInfo contextInfo, Instant loadedAt) {
        boolean isExpired() {
            return Instant.now().isAfter(loadedAt.plusSeconds(JWKS_CACHE_TTL_SECONDS));
        }
    }
}
