package cloud.plasticity.eksdx.lambda.resource;

import cloud.plasticity.eksdx.lambda.service.DynamoDbClusterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClusterResourceTest {

    @Mock
    DynamoDbClusterService clusterService;

    ClusterResource resource;

    @BeforeEach
    void setUp() {
        resource = new ClusterResource();
        resource.clusterService = clusterService;
    }

    // --- registerCluster ---

    @Test
    void registerCluster_returns201_onSuccess() {
        when(clusterService.registerCluster("test", "https://oidc.example.com", "{\"keys\":[]}"))
            .thenReturn(Map.of("clusterName", "test", "issuer", "https://oidc.example.com"));

        var req = new ClusterResource.RegisterClusterRequest();
        req.name = "test";
        req.issuer = "https://oidc.example.com";
        req.jwks = "{\"keys\":[]}";

        try (Response resp = resource.registerCluster(req)) {
            assertEquals(201, resp.getStatus());
        }
    }

    @Test
    void registerCluster_returns400_whenNullRequest() {
        try (Response resp = resource.registerCluster(null)) {
            assertEquals(400, resp.getStatus());
        }
    }

    @Test
    void registerCluster_returns400_onInvalidInput() {
        when(clusterService.registerCluster(null, null, null))
            .thenThrow(new IllegalArgumentException("clusterName is required"));

        var req = new ClusterResource.RegisterClusterRequest();
        try (Response resp = resource.registerCluster(req)) {
            assertEquals(400, resp.getStatus());
        }
    }

    @Test
    void registerCluster_returns409_onDuplicate() {
        when(clusterService.registerCluster("dup", "https://oidc.example.com", "{\"keys\":[]}"))
            .thenThrow(new IllegalStateException("Cluster already registered: dup"));

        var req = new ClusterResource.RegisterClusterRequest();
        req.name = "dup";
        req.issuer = "https://oidc.example.com";
        req.jwks = "{\"keys\":[]}";

        try (Response resp = resource.registerCluster(req)) {
            assertEquals(409, resp.getStatus());
        }
    }

    @Test
    void registerCluster_returns500_onUnexpectedError() {
        when(clusterService.registerCluster("test", "https://oidc.example.com", "{\"keys\":[]}"))
            .thenThrow(new RuntimeException("DynamoDB unavailable"));

        var req = new ClusterResource.RegisterClusterRequest();
        req.name = "test";
        req.issuer = "https://oidc.example.com";
        req.jwks = "{\"keys\":[]}";

        try (Response resp = resource.registerCluster(req)) {
            assertEquals(500, resp.getStatus());
        }
    }

    // --- describeCluster ---

    @Test
    void describeCluster_returns200_whenFound() {
        when(clusterService.describeCluster("test"))
            .thenReturn(Map.of("clusterName", "test", "issuer", "https://oidc.example.com"));

        try (Response resp = resource.describeCluster("test")) {
            assertEquals(200, resp.getStatus());
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) resp.getEntity();
            assertEquals("test", body.get("clusterName"));
        }
    }

    @Test
    void describeCluster_returns404_whenNotFound() {
        when(clusterService.describeCluster("missing"))
            .thenThrow(new IllegalArgumentException("Cluster not registered: missing"));

        try (Response resp = resource.describeCluster("missing")) {
            assertEquals(404, resp.getStatus());
        }
    }

    // --- listClusters ---

    @Test
    void listClusters_returns200_withClusters() {
        when(clusterService.listClusters())
            .thenReturn(List.of(Map.of("clusterName", "a"), Map.of("clusterName", "b")));

        try (Response resp = resource.listClusters()) {
            assertEquals(200, resp.getStatus());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) resp.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, String>> clusters = (List<Map<String, String>>) body.get("clusters");
            assertEquals(2, clusters.size());
        }
    }

    @Test
    void listClusters_returns200_whenEmpty() {
        when(clusterService.listClusters()).thenReturn(List.of());

        try (Response resp = resource.listClusters()) {
            assertEquals(200, resp.getStatus());
        }
    }

    // --- refreshJwks ---

    @Test
    void refreshJwks_returns200_onSuccess() {
        doNothing().when(clusterService).updateJwks("test", "{\"keys\":[]}");

        try (Response resp = resource.refreshJwks("test", Map.of("jwks", "{\"keys\":[]}"))) {
            assertEquals(200, resp.getStatus());
        }
    }

    @Test
    void refreshJwks_returns400_whenClusterNotFound() {
        doThrow(new IllegalArgumentException("Cluster not registered: missing"))
            .when(clusterService).updateJwks(eq("missing"), any());

        try (Response resp = resource.refreshJwks("missing", Map.of("jwks", "{\"keys\":[]}"))) {
            assertEquals(400, resp.getStatus());
        }
    }

    @Test
    void refreshJwks_returns400_whenJwksNull() {
        doThrow(new IllegalArgumentException("jwks is required"))
            .when(clusterService).updateJwks(eq("test"), isNull());

        try (Response resp = resource.refreshJwks("test", Map.of())) {
            assertEquals(400, resp.getStatus());
        }
    }

    // --- deregisterCluster ---

    @Test
    void deregisterCluster_returns204_onSuccess() {
        doNothing().when(clusterService).deregisterCluster("test");

        try (Response resp = resource.deregisterCluster("test")) {
            assertEquals(204, resp.getStatus());
        }
    }

    @Test
    void deregisterCluster_returns404_whenNotFound() {
        doThrow(new IllegalArgumentException("Cluster not registered: missing"))
            .when(clusterService).deregisterCluster("missing");

        try (Response resp = resource.deregisterCluster("missing")) {
            assertEquals(404, resp.getStatus());
        }
    }
}
