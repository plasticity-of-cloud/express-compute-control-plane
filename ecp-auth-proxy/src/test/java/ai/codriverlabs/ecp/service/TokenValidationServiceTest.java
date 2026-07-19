package ai.codriverlabs.ecp.service;

import io.fabric8.kubernetes.api.model.authentication.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.InOutCreateable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TokenValidationServiceTest {

    @Mock
    KubernetesClient kubernetesClient;

    @Mock
    @SuppressWarnings("rawtypes")
    InOutCreateable tokenReviews;

    TokenValidationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new TokenValidationService();
        service.setKubernetesClient(kubernetesClient);
        when(kubernetesClient.tokenReviews()).thenReturn(tokenReviews);
    }

    private TokenReview authenticatedReview(String username, String uid, String podName, String podUid) {
        Map<String, java.util.List<String>> extra = new HashMap<>();
        if (podName != null) extra.put("authentication.kubernetes.io/pod-name",
            java.util.List.of(podName));
        if (podUid != null) extra.put("authentication.kubernetes.io/pod-uid",
            java.util.List.of(podUid));

        UserInfo user = new UserInfo(extra.isEmpty() ? null : (java.util.Map) extra, null, uid, username);
        return new TokenReviewBuilder()
            .withNewStatus().withAuthenticated(true).withUser(user).endStatus()
            .build();
    }

    private TokenReview rejectedReview(String error) {
        return new TokenReviewBuilder()
            .withNewStatus().withAuthenticated(false).withError(error).endStatus()
            .build();
    }

    @Test
    @DisplayName("Should validate token via TokenReview and extract claims")
    @SuppressWarnings("unchecked")
    void testValidToken() {
        when(tokenReviews.create(any())).thenReturn(
            authenticatedReview("system:serviceaccount:default:my-sa", "sa-uid-123", "my-pod", "pod-uid-456"));

        TokenValidationService.TokenClaims claims = service.validateToken("valid-token", "my-cluster");

        assertEquals("default", claims.getNamespace());
        assertEquals("my-sa", claims.getServiceAccount());
        assertEquals("sa-uid-123", claims.getServiceAccountUid());
        assertEquals("my-pod", claims.getPodName());
        assertEquals("pod-uid-456", claims.getPodUid());
    }

    @Test
    @DisplayName("Should strip Bearer prefix before sending to TokenReview")
    @SuppressWarnings("unchecked")
    void testBearerPrefixStripped() {
        when(tokenReviews.create(any())).thenReturn(
            authenticatedReview("system:serviceaccount:default:my-sa", null, null, null));

        service.validateToken("Bearer actual-token", "my-cluster");

        verify(tokenReviews).create(argThat(r -> {
            TokenReview tr = (TokenReview) r;
            return "actual-token".equals(tr.getSpec().getToken());
        }));
    }

    @Test
    @DisplayName("Should send correct audience in TokenReview spec")
    @SuppressWarnings("unchecked")
    void testAudienceSentToTokenReview() {
        when(tokenReviews.create(any())).thenReturn(
            authenticatedReview("system:serviceaccount:default:my-sa", null, null, null));

        service.validateToken("token", "my-cluster");

        verify(tokenReviews).create(argThat(r -> {
            TokenReview tr = (TokenReview) r;
            return tr.getSpec().getAudiences().contains(TokenValidationService.EKS_POD_IDENTITY_AUDIENCE);
        }));
    }

    @Test
    @DisplayName("Should reject unauthenticated TokenReview response")
    @SuppressWarnings("unchecked")
    void testRejectedToken() {
        when(tokenReviews.create(any())).thenReturn(rejectedReview("token has expired"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.validateToken("bad-token", "my-cluster"));
        assertTrue(ex.getMessage().contains("token has expired"));
    }

    @Test
    @DisplayName("Should reject null status in TokenReview response")
    @SuppressWarnings("unchecked")
    void testNullStatus() {
        when(tokenReviews.create(any())).thenReturn(new TokenReviewBuilder().build());

        assertThrows(IllegalArgumentException.class,
            () -> service.validateToken("token", "my-cluster"));
    }

    @Test
    @DisplayName("Should build correct session tags from claims")
    @SuppressWarnings("unchecked")
    void testSessionTags() {
        when(tokenReviews.create(any())).thenReturn(
            authenticatedReview("system:serviceaccount:prod:worker", null, "worker-pod", null));

        TokenValidationService.TokenClaims claims = service.validateToken("token", "my-cluster");

        Map<String, String> tags = claims.getSessionTags();
        assertEquals("prod", tags.get("kubernetes-namespace"));
        assertEquals("worker", tags.get("kubernetes-service-account"));
        assertEquals("worker-pod", tags.get("kubernetes-pod-name"));
        assertEquals("", tags.get("kubernetes-pod-uid"));
    }

    @Test
    @DisplayName("Should handle missing optional pod claims")
    @SuppressWarnings("unchecked")
    void testMissingOptionalClaims() {
        when(tokenReviews.create(any())).thenReturn(
            authenticatedReview("system:serviceaccount:default:my-sa", null, null, null));

        TokenValidationService.TokenClaims claims = service.validateToken("token", "my-cluster");

        assertNull(claims.getPodName());
        assertNull(claims.getPodUid());
    }
}
