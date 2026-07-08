package ai.codriverlabs.eksdx.tenant.resource;

import ai.codriverlabs.eksdx.tenant.exception.ClusterAlreadyExistsException;
import ai.codriverlabs.eksdx.tenant.model.TenantItem;
import ai.codriverlabs.eksdx.tenant.service.TenantCryptoService;
import ai.codriverlabs.eksdx.tenant.service.TenantProvisioningService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Unified cluster lifecycle API (both managed and self-managed).
 * Server infers mode: if jwks+issuer are present → self-managed, otherwise → managed.
 *
 * POST   /clusters         → create cluster
 * DELETE /clusters/{name}  → delete cluster (server determines teardown scope from record)
 */
@Path("/clusters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClusterResource {

    private static final Logger LOG = Logger.getLogger(ClusterResource.class);

    @Inject TenantProvisioningService provisioningService;

    public static class CreateClusterRequest {
        @JsonProperty("clusterName") public String clusterName;
        // Managed mode fields
        @JsonProperty("arch") public String arch;
        @JsonProperty("ec2PricingModel") public String ec2PricingModel;
        @JsonProperty("k8sVersion") public String k8sVersion;
        @JsonProperty("assignElasticIp") public Boolean assignElasticIp;
        @JsonProperty("diskSizeGb") public Integer diskSizeGb;
        @JsonProperty("sshCidr") public String sshCidr;
        // Self-managed mode fields (presence triggers self-managed)
        @JsonProperty("jwks") public String jwks;
        @JsonProperty("issuer") public String issuer;
    }

    @POST
    public Response createCluster(CreateClusterRequest request, @Context ContainerRequestContext ctx) {
        try {
            if (request == null || request.clusterName == null || request.clusterName.isBlank())
                return error(400, "InvalidParameterException", "clusterName is required");
            if (!request.clusterName.matches("^[a-zA-Z][a-zA-Z0-9-]{0,99}$"))
                return error(400, "InvalidParameterException",
                    "clusterName must start with a letter, contain only [a-zA-Z0-9-], max 100 chars");

            String callerArn = (String) ctx.getProperty("callerArn");
            if (callerArn == null || callerArn.isBlank())
                return error(403, "AccessDeniedException", "Cannot resolve caller identity");

            // Server infers mode: jwks present → self-managed, otherwise → managed
            boolean selfManaged = (request.jwks != null && !request.jwks.isBlank());

            if (selfManaged) {
                return createSelfManaged(request, callerArn);
            } else {
                return createManaged(request, ctx, callerArn);
            }
        } catch (ClusterAlreadyExistsException e) {
            return error(409, "ResourceInUseException", e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(400, "InvalidParameterException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Create cluster error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    private Response createManaged(CreateClusterRequest request, ContainerRequestContext ctx, String callerArn) {
        if (request.jwks != null || request.issuer != null)
            return error(400, "InvalidParameterException",
                "jwks and issuer cannot be specified in managed mode. The control plane manages PKI material.");

        String idcUserId = (String) ctx.getProperty("idcUserId");
        if (idcUserId == null || idcUserId.isBlank()) idcUserId = callerArn;

        int existing = provisioningService.countTenantsByOwner(callerArn);
        if (existing >= provisioningService.getMaxTenantsPerCaller())
            return error(429, "QuotaExceededException",
                "Quota exceeded: maximum " + provisioningService.getMaxTenantsPerCaller() + " cluster(s) per caller");

        String arch = request.arch != null ? request.arch : "arm64";
        String pricing = request.ec2PricingModel != null ? request.ec2PricingModel : "spot";
        String k8sVersion = request.k8sVersion != null ? request.k8sVersion : "1.35";

        if (!arch.equals("arm64") && !arch.equals("x86_64"))
            return error(400, "InvalidParameterException", "arch must be arm64 or x86_64");
        if (!pricing.equals("spot") && !pricing.equals("ondemand"))
            return error(400, "InvalidParameterException", "ec2PricingModel must be spot or ondemand");

        String sshCidr = request.sshCidr;
        if (sshCidr == null || sshCidr.isBlank()) {
            String sourceIp = (String) ctx.getProperty("sourceIp");
            if (sourceIp == null || sourceIp.isBlank())
                return error(400, "InvalidParameterException", "Cannot determine caller IP; provide sshCidr");
            sshCidr = sourceIp + "/32";
        }

        String id = provisioningService.provision(request.clusterName, true, idcUserId, callerArn,
            arch, pricing, k8sVersion,
            Boolean.TRUE.equals(request.assignElasticIp),
            request.diskSizeGb != null ? request.diskSizeGb : 20, sshCidr);

        return Response.accepted(Map.of("tenantId", id, "clusterName", request.clusterName, "managed", true)).build();
    }

    private Response createSelfManaged(CreateClusterRequest request, String callerArn) {
        if (request.jwks == null || request.jwks.isBlank())
            return error(400, "InvalidParameterException", "jwks is required for self-managed mode");
        if (request.issuer == null || request.issuer.isBlank())
            return error(400, "InvalidParameterException", "issuer is required for self-managed mode");

        String id = provisioningService.registerSelfManagedCluster(
            request.clusterName, request.issuer, request.jwks, callerArn);

        return Response.status(201).entity(
            Map.of("tenantId", id, "clusterName", request.clusterName, "managed", false)).build();
    }

    @DELETE
    @Path("/{name}")
    public Response deleteCluster(@PathParam("name") String name, @Context ContainerRequestContext ctx) {
        try {
            String callerArn = (String) ctx.getProperty("callerArn");
            provisioningService.deleteCluster(name, callerArn);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return error(404, "NotFoundException", e.getMessage());
        } catch (SecurityException e) {
            return error(403, "AccessDeniedException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Delete cluster error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @GET
    @Path("/{name}")
    public Response getCluster(@PathParam("name") String name, @Context ContainerRequestContext ctx) {
        try {
            String callerArn = (String) ctx.getProperty("callerArn");
            TenantItem item = provisioningService.getStateByClusterName(name);
            if (callerArn != null && item.ownerArn() != null && !callerArn.equals(item.ownerArn()))
                return error(404, "NotFoundException", "Cluster not found: " + name);
            return Response.ok(item).build();
        } catch (IllegalArgumentException e) {
            return error(404, "NotFoundException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Get cluster error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @POST
    @Path("/{name}/stop")
    public Response stopCluster(@PathParam("name") String name, @Context ContainerRequestContext ctx) {
        try {
            String callerArn = (String) ctx.getProperty("callerArn");
            provisioningService.stopByClusterName(name, callerArn);
            return Response.accepted(Map.of("clusterName", name, "status", "stopping")).build();
        } catch (IllegalArgumentException e) {
            return error(404, "NotFoundException", e.getMessage());
        } catch (SecurityException e) {
            return error(403, "AccessDeniedException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Stop cluster error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @POST
    @Path("/{name}/resume")
    public Response resumeCluster(@PathParam("name") String name, @Context ContainerRequestContext ctx) {
        try {
            String callerArn = (String) ctx.getProperty("callerArn");
            provisioningService.resumeByClusterName(name, callerArn);
            return Response.accepted(Map.of("clusterName", name, "status", "resuming")).build();
        } catch (IllegalArgumentException e) {
            return error(404, "NotFoundException", e.getMessage());
        } catch (SecurityException e) {
            return error(403, "AccessDeniedException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Resume cluster error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    private Response error(int status, String code, String message) {
        return Response.status(status).entity(Map.of("__type", code, "message", message)).build();
    }
}
