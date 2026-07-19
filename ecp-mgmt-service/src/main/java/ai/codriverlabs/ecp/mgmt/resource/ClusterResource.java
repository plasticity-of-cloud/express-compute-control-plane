package ai.codriverlabs.ecp.mgmt.resource;

import ai.codriverlabs.ecp.mgmt.service.DynamoDbClusterService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

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

    // POST /clusters removed — cluster creation is now handled by tenant-service.
    // Kept: GET, PUT /jwks, DELETE for backward compatibility.

    @GET
    @Path("/{name}")
    public Response describeCluster(@PathParam("name") String name, @Context ContainerRequestContext ctx) {
        try {
            String callerArn = (String) ctx.getProperty("callerArn");
            String ownerArn = clusterService.getOwnerArn(name);
            if (callerArn != null && ownerArn != null && !callerArn.equals(ownerArn))
                return error(404, "NotFoundException", "Cluster not found: " + name);
            return Response.ok(clusterService.describeCluster(name)).build();
        } catch (IllegalArgumentException e) {
            return error(404, "NotFoundException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Describe cluster error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @GET
    public Response listClusters(@Context ContainerRequestContext ctx) {
        try {
            String callerArn = (String) ctx.getProperty("callerArn");
            List<Map<String, String>> result = callerArn != null
                ? clusterService.listClustersByOwner(callerArn)
                : clusterService.listClusters();
            return Response.ok(Map.of("clusters", result)).build();
        } catch (Exception e) {
            LOG.errorf("List clusters error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @PUT
    @Path("/{name}/jwks")
    public Response refreshJwks(@PathParam("name") String name, Map<String, Object> body,
                                 @Context ContainerRequestContext ctx) {
        try {
            String callerArn = (String) ctx.getProperty("callerArn");
            String ownerArn = clusterService.getOwnerArn(name);
            if (callerArn != null && ownerArn != null && !callerArn.equals(ownerArn))
                return error(404, "NotFoundException", "Cluster not found: " + name);
            String jwks = body != null && body.containsKey("jwks") ? body.get("jwks").toString() : null;
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
    public Response deregisterCluster(@PathParam("name") String name, @Context ContainerRequestContext ctx) {
        try {
            String callerArn = (String) ctx.getProperty("callerArn");
            String ownerArn = clusterService.getOwnerArn(name);
            if (callerArn != null && ownerArn != null && !callerArn.equals(ownerArn))
                return error(404, "NotFoundException", "Cluster not found: " + name);
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
