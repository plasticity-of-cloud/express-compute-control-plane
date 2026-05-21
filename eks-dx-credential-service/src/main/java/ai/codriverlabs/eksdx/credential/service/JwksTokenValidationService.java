package ai.codriverlabs.eksdx.credential.service;

import ai.codriverlabs.eksdx.model.TokenClaims;
import io.smallrye.jwt.auth.principal.JWTAuthContextInfo;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates Kubernetes SA tokens using JWKS stored in DynamoDB.
 * JWKS is cached per cluster with a 5-minute TTL.
 */
@ApplicationScoped
public class JwksTokenValidationService {

    private static final Logger LOG = Logger.getLogger(JwksTokenValidationService.class);
    private static final String AUDIENCE = "pods.eks.amazonaws.com";
    private static final long CACHE_TTL_SECONDS = 300;

    @Inject DynamoDbClient dynamoDb;
    @Inject JWTParser jwtParser;

    @ConfigProperty(name = "eks-dx.clusters-table")
    String clustersTable;

    private final Map<String, CachedContext> cache = new ConcurrentHashMap<>();

    public TokenClaims validateToken(String token, String clusterName) {
        if (token.startsWith("Bearer ")) token = token.substring(7);
        JWTAuthContextInfo ctx = getContextInfo(clusterName);
        try {
            JsonWebToken jwt = jwtParser.parse(token, ctx);
            return extractClaims(jwt);
        } catch (ParseException e) {
            LOG.warnf("JWT validation failed for cluster %s: %s", clusterName, e.getMessage());
            throw new IllegalArgumentException("Invalid token: " + e.getMessage(), e);
        }
    }

    private JWTAuthContextInfo getContextInfo(String clusterName) {
        CachedContext cached = cache.get(clusterName);
        if (cached != null && !cached.isExpired()) return cached.contextInfo;

        GetItemResponse resp = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(clustersTable)
            .key(Map.of("clusterName", AttributeValue.fromS(clusterName)))
            .build());
        if (!resp.hasItem() || resp.item().isEmpty())
            throw new IllegalArgumentException("Cluster not registered: " + clusterName);

        JWTAuthContextInfo info = new JWTAuthContextInfo();
        info.setPublicKeyContent(resp.item().get("jwks").s());
        info.setIssuedBy(resp.item().get("issuer").s());
        info.setExpectedAudience(Set.of(AUDIENCE));
        info.setRequireNamedPrincipal(true);

        cache.put(clusterName, new CachedContext(info, Instant.now()));
        LOG.infof("JWKS context loaded for cluster %s", clusterName);
        return info;
    }

    private TokenClaims extractClaims(JsonWebToken jwt) {
        String subject = jwt.getSubject();
        String[] parts = subject.split(":");
        if (parts.length != 4 || !"system".equals(parts[0]) || !"serviceaccount".equals(parts[1]))
            throw new IllegalArgumentException("Unexpected subject format: " + subject);
        return new TokenClaims(parts[2], parts[3],
            jwt.getClaim("kubernetes.io/serviceaccount/uid"),
            jwt.getClaim("kubernetes.io/pod/name"),
            jwt.getClaim("kubernetes.io/pod/uid"),
            subject);
    }

    private record CachedContext(JWTAuthContextInfo contextInfo, Instant loadedAt) {
        boolean isExpired() { return Instant.now().isAfter(loadedAt.plusSeconds(CACHE_TTL_SECONDS)); }
    }
}
