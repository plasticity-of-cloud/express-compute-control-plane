package ai.codriverlabs.ecp.mgmt.service;

import ai.codriverlabs.ecp.model.TokenClaims;
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
 * Validates webhook SA tokens for management API access.
 * Audience: ecp.codriverlabs.ai
 */
@ApplicationScoped
public class JwksTokenValidationService {

    private static final Logger LOG = Logger.getLogger(JwksTokenValidationService.class);
    private static final String ECP_AUDIENCE = "express-compute.codriverlabs.ai";
    private static final long CACHE_TTL_SECONDS = 300;

    @Inject DynamoDbClusterService clusterService;
    @Inject JWTParser jwtParser;

    private final Map<String, CachedContext> cache = new ConcurrentHashMap<>();

    public void validateWebhookToken(String token, String clusterName, String expectedSubject) {
        if (token.startsWith("Bearer ")) token = token.substring(7);
        JWTAuthContextInfo ctx = getContextInfo(clusterName);
        try {
            JsonWebToken jwt = jwtParser.parse(token, ctx);
            String subject = jwt.getSubject();
            if (!expectedSubject.equals(subject))
                throw new SecurityException("Unexpected subject: " + subject);
        } catch (ParseException e) {
            LOG.warnf("Webhook JWT validation failed for cluster %s: %s", clusterName, e.getMessage());
            throw new IllegalArgumentException("Invalid token: " + e.getMessage(), e);
        }
    }

    private JWTAuthContextInfo getContextInfo(String clusterName) {
        CachedContext cached = cache.get(clusterName);
        if (cached != null && !cached.isExpired()) return cached.contextInfo;

        JWTAuthContextInfo info = new JWTAuthContextInfo();
        info.setPublicKeyContent(clusterService.getJwks(clusterName));
        info.setIssuedBy(clusterService.getIssuer(clusterName));
        info.setExpectedAudience(Set.of(ECP_AUDIENCE));
        info.setRequireNamedPrincipal(true);

        cache.put(clusterName, new CachedContext(info, Instant.now()));
        LOG.infof("JWKS context loaded for cluster %s (webhook)", clusterName);
        return info;
    }

    private record CachedContext(JWTAuthContextInfo contextInfo, Instant loadedAt) {
        boolean isExpired() { return Instant.now().isAfter(loadedAt.plusSeconds(CACHE_TTL_SECONDS)); }
    }
}
