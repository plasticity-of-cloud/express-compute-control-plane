package cloud.plasticity.eksdx.lambda.resource;

import cloud.plasticity.eksdx.lambda.service.DynamoDbClusterService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Cluster registration and management API.
 */
@Path("/clusters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClusterResource {

    private static final Logger LOG = Logger.getLogger(ClusterResource.class);

    @Inject DynamoDbClusterService clusterService;

    public static class RegisterClusterRequest {
        @JsonProperty("name") public String name;
        @JsonProperty("issuer") public String issuer;
        @JsonProperty("jwks") public String jwks;
    }

    @POST
    public Response registerCluster(RegisterClusterRequest request) {
        try {
            if (request == null) {
                return error(400, "InvalidParameterException", "Request body is required");
            }
            Map<String, String> result = clusterService.registerCluster(
                request.name, request.issuer, request.jwks);
            return Response.status(201).entity(result).build();
        } catch (IllegalArgumentException e) {
            return error(400, "InvalidParameterException", e.getMessage());
        } catch (IllegalStateException e) {
            return error(409, "ConflictException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Register cluster error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @GET
    @Path("/{name}")
    public Response describeCluster(@PathParam("name") String name) {
        try {
            Map<String, String> result = clusterService.describeCluster(name);
            return Response.ok(result).build();
        } catch (IllegalArgumentException e) {
            return error(404, "NotFoundException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Describe cluster error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @GET
    public Response listClusters() {
        try {
            List<Map<String, String>> result = clusterService.listClusters();
            return Response.ok(Map.of("clusters", result)).build();
        } catch (Exception e) {
            LOG.errorf("List clusters error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @PUT
    @Path("/{name}/jwks")
    public Response refreshJwks(@PathParam("name") String name, Map<String, Object> body) {
        try {
            String jwks = body != null && body.containsKey("jwks")
                ? body.get("jwks").toString() : null;
            clusterService.updateJwks(name, jwks);
            return Response.ok(Map.of("clusterName", name, "status", "updated")).build();
        } catch (IllegalArgumentException e) {
            return error(400, "InvalidParameterException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Update JWKS error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @DELETE
    @Path("/{name}")
    public Response deregisterCluster(@PathParam("name") String name) {
        try {
            clusterService.deregisterCluster(name);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return error(404, "NotFoundException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Deregister cluster error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    private Response error(int status, String code, String message) {
        return Response.status(status).entity(Map.of("__type", code, "message", message)).build();
    }
}
