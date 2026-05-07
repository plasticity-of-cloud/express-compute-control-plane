package cloud.plasticity.eksdx.lambda.service;

import cloud.plasticity.eksdx.lambda.model.TokenClaims;
import io.smallrye.jwt.auth.principal.DefaultJWTParser;
import io.smallrye.jwt.auth.principal.JWTParser;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwksTokenValidationServiceTest {

    private static final String CLUSTER = "test-cluster";
    private static final String ISSUER = "https://oidc.example.com";
    private static final String POD_AUDIENCE = "pods.eks.amazonaws.com";
    private static final String DX_AUDIENCE = "eks-dx.plasticity.cloud";

    private static RsaJsonWebKey rsaKey;
    private static String jwksJson;

    @Mock
    DynamoDbClusterService clusterService;

    JwksTokenValidationService service;

    @BeforeAll
    static void generateKeys() throws Exception {
        rsaKey = RsaJwkGenerator.generateJwk(2048);
        rsaKey.setKeyId("test-key-1");
        rsaKey.setAlgorithm(AlgorithmIdentifiers.RSA_USING_SHA256);
        JsonWebKeySet jwks = new JsonWebKeySet(rsaKey);
        jwksJson = jwks.toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY);
    }

    @BeforeEach
    void setUp() {
        service = new JwksTokenValidationService();
        service.clusterService = clusterService;
        service.jwtParser = new DefaultJWTParser();
    }

    // --- validateToken (pod identity audience) ---

    @Test
    void validateToken_succeeds_withValidToken() throws Exception {
        mockCluster();
        String token = createToken(POD_AUDIENCE, ISSUER,
            "system:serviceaccount:default:my-sa", 60);

        TokenClaims claims = service.validateToken(token, CLUSTER);

        assertEquals("default", claims.namespace());
        assertEquals("my-sa", claims.serviceAccount());
        assertEquals("system:serviceaccount:default:my-sa", claims.subject());
    }

    @Test
    void validateToken_succeeds_withBearerPrefix() throws Exception {
        mockCluster();
        String token = "Bearer " + createToken(POD_AUDIENCE, ISSUER,
            "system:serviceaccount:kube-system:webhook-sa", 60);

        TokenClaims claims = service.validateToken(token, CLUSTER);

        assertEquals("kube-system", claims.namespace());
        assertEquals("webhook-sa", claims.serviceAccount());
    }

    @Test
    void validateToken_extractsOptionalClaims() throws Exception {
        mockCluster();
        JwtClaims jwtClaims = new JwtClaims();
        jwtClaims.setIssuer(ISSUER);
        jwtClaims.setAudience(POD_AUDIENCE);
        jwtClaims.setSubject("system:serviceaccount:default:my-sa");
        jwtClaims.setExpirationTimeMinutesInTheFuture(10);
        jwtClaims.setIssuedAtToNow();
        jwtClaims.setStringClaim("kubernetes.io/pod/name", "my-pod-abc123");
        jwtClaims.setStringClaim("kubernetes.io/pod/uid", "uid-123");
        jwtClaims.setStringClaim("kubernetes.io/serviceaccount/uid", "sa-uid-456");
        String token = signClaims(jwtClaims);

        TokenClaims claims = service.validateToken(token, CLUSTER);

        assertEquals("my-pod-abc123", claims.podName());
        assertEquals("uid-123", claims.podUid());
        assertEquals("sa-uid-456", claims.serviceAccountUid());
    }

    @Test
    void validateToken_handlesNullOptionalClaims() throws Exception {
        mockCluster();
        String token = createToken(POD_AUDIENCE, ISSUER,
            "system:serviceaccount:default:my-sa", 60);

        TokenClaims claims = service.validateToken(token, CLUSTER);

        assertNull(claims.podName());
        assertNull(claims.podUid());
        assertNull(claims.serviceAccountUid());
    }

    @Test
    void validateToken_throws_whenTokenExpired() throws Exception {
        mockCluster();
        String token = createToken(POD_AUDIENCE, ISSUER,
            "system:serviceaccount:default:my-sa", -5);

        assertThrows(IllegalArgumentException.class,
            () -> service.validateToken(token, CLUSTER));
    }

    @Test
    void validateToken_throws_whenWrongAudience() throws Exception {
        mockCluster();
        String token = createToken("wrong-audience", ISSUER,
            "system:serviceaccount:default:my-sa", 60);

        assertThrows(IllegalArgumentException.class,
            () -> service.validateToken(token, CLUSTER));
    }

    @Test
    void validateToken_throws_whenWrongIssuer() throws Exception {
        mockCluster();
        String token = createToken(POD_AUDIENCE, "https://wrong-issuer.com",
            "system:serviceaccount:default:my-sa", 60);

        assertThrows(IllegalArgumentException.class,
            () -> service.validateToken(token, CLUSTER));
    }

    @Test
    void validateToken_throws_whenInvalidSignature() throws Exception {
        mockCluster();
        RsaJsonWebKey otherKey = RsaJwkGenerator.generateJwk(2048);
        otherKey.setKeyId("other-key");

        JwtClaims claims = new JwtClaims();
        claims.setIssuer(ISSUER);
        claims.setAudience(POD_AUDIENCE);
        claims.setSubject("system:serviceaccount:default:my-sa");
        claims.setExpirationTimeMinutesInTheFuture(10);
        claims.setIssuedAtToNow();

        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(otherKey.getPrivateKey());
        jws.setKeyIdHeaderValue(otherKey.getKeyId());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        String token = jws.getCompactSerialization();

        assertThrows(IllegalArgumentException.class,
            () -> service.validateToken(token, CLUSTER));
    }

    @Test
    void validateToken_throws_whenMalformedToken() {
        mockCluster();
        assertThrows(IllegalArgumentException.class,
            () -> service.validateToken("not-a-jwt", CLUSTER));
    }

    @Test
    void validateToken_throws_whenSubjectFormatInvalid() throws Exception {
        mockCluster();
        String token = createToken(POD_AUDIENCE, ISSUER, "invalid-subject", 60);

        assertThrows(IllegalArgumentException.class,
            () -> service.validateToken(token, CLUSTER));
    }

    @Test
    void validateToken_throws_whenSubjectMissingParts() throws Exception {
        mockCluster();
        String token = createToken(POD_AUDIENCE, ISSUER, "system:serviceaccount:default", 60);

        assertThrows(IllegalArgumentException.class,
            () -> service.validateToken(token, CLUSTER));
    }

    @Test
    void validateToken_throws_whenClusterNotRegistered() {
        when(clusterService.getJwks("unknown"))
            .thenThrow(new IllegalArgumentException("Cluster not registered: unknown"));

        assertThrows(IllegalArgumentException.class,
            () -> service.validateToken("some-token", "unknown"));
    }

    // --- validateWebhookToken ---

    @Test
    void validateWebhookToken_succeeds_withCorrectSubject() throws Exception {
        mockCluster();
        String expectedSa = "system:serviceaccount:kube-system:eks-dx-pod-identity-webhook";
        String token = createToken(DX_AUDIENCE, ISSUER, expectedSa, 60);

        assertDoesNotThrow(() ->
            service.validateWebhookToken(token, CLUSTER, expectedSa));
    }

    @Test
    void validateWebhookToken_throws_whenWrongSubject() throws Exception {
        mockCluster();
        String token = createToken(DX_AUDIENCE, ISSUER,
            "system:serviceaccount:default:wrong-sa", 60);

        assertThrows(SecurityException.class,
            () -> service.validateWebhookToken(token, CLUSTER,
                "system:serviceaccount:kube-system:eks-dx-pod-identity-webhook"));
    }

    @Test
    void validateWebhookToken_throws_whenWrongAudience() throws Exception {
        mockCluster();
        String token = createToken(POD_AUDIENCE, ISSUER,
            "system:serviceaccount:kube-system:eks-dx-pod-identity-webhook", 60);

        assertThrows(IllegalArgumentException.class,
            () -> service.validateWebhookToken(token, CLUSTER,
                "system:serviceaccount:kube-system:eks-dx-pod-identity-webhook"));
    }

    // --- JWKS caching ---

    @Test
    void validateToken_cachesContext_acrossCalls() throws Exception {
        mockCluster();
        String token1 = createToken(POD_AUDIENCE, ISSUER,
            "system:serviceaccount:default:sa1", 60);
        String token2 = createToken(POD_AUDIENCE, ISSUER,
            "system:serviceaccount:default:sa2", 60);

        service.validateToken(token1, CLUSTER);
        service.validateToken(token2, CLUSTER);

        // JWKS should be fetched only once (cached)
        verify(clusterService, times(1)).getJwks(CLUSTER);
    }

    // --- sessionTags from TokenClaims ---

    @Test
    void tokenClaims_sessionTags_includesAllFields() {
        TokenClaims claims = new TokenClaims("default", "my-sa", "sa-uid",
            "my-pod", "pod-uid", "system:serviceaccount:default:my-sa");

        var tags = claims.sessionTags();
        assertEquals("default", tags.get("kubernetes-namespace"));
        assertEquals("my-sa", tags.get("kubernetes-service-account"));
        assertEquals("my-pod", tags.get("kubernetes-pod-name"));
        assertEquals("pod-uid", tags.get("kubernetes-pod-uid"));
    }

    @Test
    void tokenClaims_sessionTags_handlesNullPodFields() {
        TokenClaims claims = new TokenClaims("default", "my-sa", null,
            null, null, "system:serviceaccount:default:my-sa");

        var tags = claims.sessionTags();
        assertEquals("", tags.get("kubernetes-pod-name"));
        assertEquals("", tags.get("kubernetes-pod-uid"));
    }

    // --- helpers ---

    private void mockCluster() {
        lenient().when(clusterService.getJwks(CLUSTER)).thenReturn(jwksJson);
        lenient().when(clusterService.getIssuer(CLUSTER)).thenReturn(ISSUER);
    }

    private String createToken(String audience, String issuer, String subject,
                                int expirationMinutes) throws Exception {
        JwtClaims claims = new JwtClaims();
        claims.setIssuer(issuer);
        claims.setAudience(audience);
        claims.setSubject(subject);
        claims.setExpirationTimeMinutesInTheFuture(expirationMinutes);
        claims.setIssuedAtToNow();
        return signClaims(claims);
    }

    private String signClaims(JwtClaims claims) throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(rsaKey.getPrivateKey());
        jws.setKeyIdHeaderValue(rsaKey.getKeyId());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        return jws.getCompactSerialization();
    }
}
