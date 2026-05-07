package cloud.plasticity.eksdx.lambda.auth;

import cloud.plasticity.eksdx.lambda.service.JwksTokenValidationService;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookAuthFilterTest {

    @Mock JwksTokenValidationService tokenValidationService;
    @Mock ContainerRequestContext ctx;
    @Mock UriInfo uriInfo;

    WebhookAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new WebhookAuthFilter();
        filter.tokenValidationService = tokenValidationService;
        lenient().when(ctx.getUriInfo()).thenReturn(uriInfo);
    }

    // --- paths that should NOT be filtered ---

    @Test
    void filter_skips_assetsEndpoint() {
        when(uriInfo.getPath()).thenReturn("clusters/test/assets");

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void filter_skips_clusterEndpoints() {
        when(uriInfo.getPath()).thenReturn("clusters");

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void filter_skips_nonGetMethods() {
        when(uriInfo.getPath()).thenReturn("clusters/test/pod-identity-associations");
        when(ctx.getMethod()).thenReturn("POST");

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void filter_skips_deleteMethods() {
        when(uriInfo.getPath()).thenReturn("clusters/test/pod-identity-associations/assoc-1");
        when(ctx.getMethod()).thenReturn("DELETE");

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    // --- GET on associations without Bearer token ---

    @Test
    void filter_allowsThrough_whenNoBearerToken() {
        when(uriInfo.getPath()).thenReturn("clusters/test/pod-identity-associations");
        when(ctx.getMethod()).thenReturn("GET");
        when(ctx.getHeaderString("Authorization")).thenReturn(null);

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void filter_allowsThrough_whenNonBearerAuth() {
        when(uriInfo.getPath()).thenReturn("clusters/test/pod-identity-associations");
        when(ctx.getMethod()).thenReturn("GET");
        when(ctx.getHeaderString("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    // --- GET on associations with Bearer token ---

    @Test
    void filter_validates_bearerToken_onGetAssociations() {
        when(uriInfo.getPath()).thenReturn("clusters/test-cluster/pod-identity-associations");
        when(ctx.getMethod()).thenReturn("GET");
        when(ctx.getHeaderString("Authorization")).thenReturn("Bearer valid-token");
        doNothing().when(tokenValidationService).validateWebhookToken(
            "Bearer valid-token", "test-cluster",
            "system:serviceaccount:kube-system:eks-dx-pod-identity-webhook");

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
        verify(tokenValidationService).validateWebhookToken(
            "Bearer valid-token", "test-cluster",
            "system:serviceaccount:kube-system:eks-dx-pod-identity-webhook");
    }

    @Test
    void filter_aborts403_whenTokenInvalid() {
        when(uriInfo.getPath()).thenReturn("clusters/test-cluster/pod-identity-associations");
        when(ctx.getMethod()).thenReturn("GET");
        when(ctx.getHeaderString("Authorization")).thenReturn("Bearer bad-token");
        doThrow(new SecurityException("Invalid token"))
            .when(tokenValidationService).validateWebhookToken(any(), any(), any());

        filter.filter(ctx);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        assertEquals(403, captor.getValue().getStatus());
    }

    @Test
    void filter_aborts403_whenTokenExpired() {
        when(uriInfo.getPath()).thenReturn("clusters/test-cluster/pod-identity-associations");
        when(ctx.getMethod()).thenReturn("GET");
        when(ctx.getHeaderString("Authorization")).thenReturn("Bearer expired-token");
        doThrow(new IllegalArgumentException("Token expired"))
            .when(tokenValidationService).validateWebhookToken(any(), any(), any());

        filter.filter(ctx);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        assertEquals(403, captor.getValue().getStatus());
    }

    // --- cluster name extraction ---

    @Test
    void filter_extractsClusterName_fromPath() {
        when(uriInfo.getPath()).thenReturn("clusters/my-k3s-cluster/pod-identity-associations");
        when(ctx.getMethod()).thenReturn("GET");
        when(ctx.getHeaderString("Authorization")).thenReturn("Bearer token");
        doNothing().when(tokenValidationService).validateWebhookToken(any(), any(), any());

        filter.filter(ctx);

        verify(tokenValidationService).validateWebhookToken(
            "Bearer token", "my-k3s-cluster",
            "system:serviceaccount:kube-system:eks-dx-pod-identity-webhook");
    }

    @Test
    void filter_extractsClusterName_fromNestedPath() {
        when(uriInfo.getPath()).thenReturn("clusters/prod-cluster/pod-identity-associations/assoc-1");
        when(ctx.getMethod()).thenReturn("GET");
        when(ctx.getHeaderString("Authorization")).thenReturn("Bearer token");
        doNothing().when(tokenValidationService).validateWebhookToken(any(), any(), any());

        filter.filter(ctx);

        verify(tokenValidationService).validateWebhookToken(
            "Bearer token", "prod-cluster",
            "system:serviceaccount:kube-system:eks-dx-pod-identity-webhook");
    }
}
