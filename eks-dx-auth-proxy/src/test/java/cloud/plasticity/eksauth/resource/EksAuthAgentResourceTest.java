package cloud.plasticity.eksauth.resource;

import cloud.plasticity.eksauth.service.LambdaForwardingService;
import cloud.plasticity.eksauth.service.TokenValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import jakarta.ws.rs.core.Response;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EksAuthAgentResourceTest {

    @Mock TokenValidationService tokenValidationService;
    @Mock LambdaForwardingService forwardingService;

    EksAuthAgentResource resource;

    @BeforeEach
    void setUp() {
        resource = new EksAuthAgentResource();
        resource.tokenValidationService = tokenValidationService;
        resource.forwardingService = forwardingService;
    }

    @Test
    void assumeRole_returns200_whenLambdaSucceeds() {
        when(tokenValidationService.validateToken("valid-token", "cluster"))
            .thenReturn(new TokenValidationService.TokenClaims(
                "default", "my-sa", null, null, null, "system:serviceaccount:default:my-sa", null));
        when(forwardingService.forward(eq("cluster"), any()))
            .thenReturn(new LambdaForwardingService.ForwardResult(200, "{\"credentials\":{}}"));

        var req = new EksAuthAgentResource.AgentRequest();
        req.token = "valid-token";

        try (Response resp = resource.assumeRoleForPodIdentity("cluster", req)) {
            assertEquals(200, resp.getStatus());
        }
    }

    @Test
    void assumeRole_forwardsToLambda_afterTokenReview() {
        when(tokenValidationService.validateToken("valid-token", "cluster"))
            .thenReturn(new TokenValidationService.TokenClaims(
                "default", "my-sa", null, null, null, "system:serviceaccount:default:my-sa", null));
        when(forwardingService.forward(eq("cluster"), any()))
            .thenReturn(new LambdaForwardingService.ForwardResult(200, "{}"));

        var req = new EksAuthAgentResource.AgentRequest();
        req.token = "valid-token";
        resource.assumeRoleForPodIdentity("cluster", req);

        verify(tokenValidationService).validateToken("valid-token", "cluster");
        verify(forwardingService).forward(eq("cluster"), contains("valid-token"));
    }

    @Test
    void assumeRole_returns400_whenTokenNull() {
        var req = new EksAuthAgentResource.AgentRequest();
        req.token = null;

        try (Response resp = resource.assumeRoleForPodIdentity("cluster", req)) {
            assertEquals(400, resp.getStatus());
        }
        verifyNoInteractions(tokenValidationService, forwardingService);
    }

    @Test
    void assumeRole_returns400_whenTokenEmpty() {
        var req = new EksAuthAgentResource.AgentRequest();
        req.token = "";

        try (Response resp = resource.assumeRoleForPodIdentity("cluster", req)) {
            assertEquals(400, resp.getStatus());
        }
    }

    @Test
    void assumeRole_returns400_whenRequestNull() {
        try (Response resp = resource.assumeRoleForPodIdentity("cluster", null)) {
            assertEquals(400, resp.getStatus());
        }
    }

    @Test
    void assumeRole_returns400_whenTokenReviewRejects() {
        when(tokenValidationService.validateToken("bad-token", "cluster"))
            .thenThrow(new IllegalArgumentException("Invalid token"));

        var req = new EksAuthAgentResource.AgentRequest();
        req.token = "bad-token";

        try (Response resp = resource.assumeRoleForPodIdentity("cluster", req)) {
            assertEquals(400, resp.getStatus());
        }
        verifyNoInteractions(forwardingService);
    }

    @Test
    void assumeRole_returns403_whenSecurityException() {
        when(tokenValidationService.validateToken("forbidden-token", "cluster"))
            .thenThrow(new SecurityException("Access denied"));

        var req = new EksAuthAgentResource.AgentRequest();
        req.token = "forbidden-token";

        try (Response resp = resource.assumeRoleForPodIdentity("cluster", req)) {
            assertEquals(403, resp.getStatus());
        }
    }

    @Test
    void assumeRole_returns500_whenForwardingFails() {
        when(tokenValidationService.validateToken("valid-token", "cluster"))
            .thenReturn(new TokenValidationService.TokenClaims(
                "default", "my-sa", null, null, null, "system:serviceaccount:default:my-sa", null));
        when(forwardingService.forward(eq("cluster"), any()))
            .thenThrow(new RuntimeException("Lambda unreachable"));

        var req = new EksAuthAgentResource.AgentRequest();
        req.token = "valid-token";

        try (Response resp = resource.assumeRoleForPodIdentity("cluster", req)) {
            assertEquals(500, resp.getStatus());
        }
    }

    @Test
    void assumeRole_propagatesLambdaErrorStatus() {
        when(tokenValidationService.validateToken("valid-token", "cluster"))
            .thenReturn(new TokenValidationService.TokenClaims(
                "default", "my-sa", null, null, null, "system:serviceaccount:default:my-sa", null));
        when(forwardingService.forward(eq("cluster"), any()))
            .thenReturn(new LambdaForwardingService.ForwardResult(404,
                "{\"__type\":\"NotFoundException\",\"message\":\"No association\"}"));

        var req = new EksAuthAgentResource.AgentRequest();
        req.token = "valid-token";

        try (Response resp = resource.assumeRoleForPodIdentity("cluster", req)) {
            assertEquals(404, resp.getStatus());
        }
    }
}
