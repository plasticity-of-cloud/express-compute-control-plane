package ai.codriverlabs.eksdx.mgmt.resource;

import ai.codriverlabs.eksdx.mgmt.service.DynamoDbAssociationService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

@Path("/clusters/{clusterName}/pod-identity-associations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AssociationResource {

    private static final Logger LOG = Logger.getLogger(AssociationResource.class);

    @Inject DynamoDbAssociationService associationService;

    public static class CreateAssociationRequest {
        @JsonProperty("namespace") public String namespace;
        @JsonProperty("serviceAccount") public String serviceAccount;
        @JsonProperty("roleArn") public String roleArn;
    }

    @POST
    public Response createAssociation(@PathParam("clusterName") String clusterName,
                                       CreateAssociationRequest request) {
        try {
            if (request == null) return error(400, "InvalidParameterException", "Request body is required");
            Map<String, String> result = associationService.createAssociation(
                clusterName, request.namespace, request.serviceAccount, request.roleArn);
            return Response.status(201).entity(result).build();
        } catch (IllegalArgumentException e) {
            return error(400, "InvalidParameterException", e.getMessage());
        } catch (IllegalStateException e) {
            return error(409, "ConflictException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Create association error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @GET
    public Response listAssociations(@PathParam("clusterName") String clusterName,
                                      @QueryParam("namespace") String namespace,
                                      @QueryParam("serviceAccount") String serviceAccount) {
        try {
            List<Map<String, String>> result = associationService.listAssociations(
                clusterName, namespace, serviceAccount);
            return Response.ok(Map.of("associations", result)).build();
        } catch (Exception e) {
            LOG.errorf("List associations error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @GET
    @Path("/{associationId}")
    public Response describeAssociation(@PathParam("clusterName") String clusterName,
                                         @PathParam("associationId") String associationId) {
        try {
            Map<String, String> result = associationService.describeAssociation(clusterName, associationId);
            if (result == null) return error(404, "NotFoundException", "Association not found: " + associationId);
            return Response.ok(result).build();
        } catch (Exception e) {
            LOG.errorf("Describe association error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @DELETE
    @Path("/{associationId}")
    public Response deleteAssociation(@PathParam("clusterName") String clusterName,
                                       @PathParam("associationId") String associationId) {
        try {
            associationService.deleteAssociation(clusterName, associationId);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return error(404, "NotFoundException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Delete association error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    private Response error(int status, String code, String message) {
        return Response.status(status).entity(Map.of("__type", code, "message", message)).build();
    }
}
