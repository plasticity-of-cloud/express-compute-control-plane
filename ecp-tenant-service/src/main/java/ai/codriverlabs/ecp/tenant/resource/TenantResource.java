package ai.codriverlabs.ecp.tenant.resource;

import ai.codriverlabs.ecp.tenant.model.TenantItem;
import ai.codriverlabs.ecp.tenant.service.TenantProvisioningService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Tenant lifecycle API.
 * POST /tenants        → 202 { tenantId }
 * GET  /tenants/{id}   → current state
 * DELETE /tenants/{id} → deprovision
 *
 * All endpoints require IAM auth (configured in API Gateway / sam.yaml).
 * The SSE stream endpoint lives in TenantStreamResource (Lambda Function URL).
 */
@Path("/tenants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TenantResource {

    private static final Logger LOG = Logger.getLogger(TenantResource.class);

    @Inject TenantProvisioningService provisioningService;
    @Inject ai.codriverlabs.ecp.tenant.service.DryRunProvisioningService dryRunService;

    public static class CreateTenantRequest {
        @JsonProperty("clusterName") public String clusterName;
        @JsonProperty("managed") public Boolean managed;
        @JsonProperty("arch") public String arch;
        @JsonProperty("ec2PricingModel") public String ec2PricingModel;
        @JsonProperty("k8sVersion") public String k8sVersion;
        @JsonProperty("assignElasticIp") public Boolean assignElasticIp;
        @JsonProperty("diskSizeGb") public Integer diskSizeGb;
        @JsonProperty("sshCidr") public String sshCidr;
    }

    @POST
    public Response createTenant(CreateTenantRequest request, @Context ContainerRequestContext ctx) {
        try {
            if (request == null || request.clusterName == null || request.clusterName.isBlank())
                return error(400, "InvalidParameterException", "clusterName is required");
            if (!request.clusterName.matches("^[a-zA-Z][a-zA-Z0-9-]{0,99}$"))
                return error(400, "InvalidParameterException",
                    "clusterName must start with a letter, contain only [a-zA-Z0-9-], max 100 chars");

            String callerArn = (String) ctx.getProperty("callerArn");
            if (callerArn == null || callerArn.isBlank())
                return error(403, "AccessDeniedException", "Cannot resolve caller identity");

            String idcUserId = (String) ctx.getProperty("idcUserId");
            if (idcUserId == null || idcUserId.isBlank())
                idcUserId = callerArn; // fallback: use ARN as stable identity input

            boolean managed = !Boolean.FALSE.equals(request.managed); // default true

            // Quota enforcement (only for managed tenants — unmanaged have no EC2 cost)
            if (managed) {
                int existing = provisioningService.countTenantsByOwner(callerArn);
                if (existing >= provisioningService.getMaxTenantsPerCaller())
                    return error(429, "QuotaExceededException",
                        "Quota exceeded: maximum " + provisioningService.getMaxTenantsPerCaller()
                        + " tenant(s) per caller, you have " + existing);
            }

            if (dryRunService.isEnabled()) {
                LOG.infof("DRY RUN: simulating provision for cluster %s", request.clusterName);
                return Response.accepted(Map.of("tenantId", "dryrun00", "clusterName", request.clusterName, "managed", managed)).build();
            }

            if (managed) {
                String arch = request.arch != null ? request.arch : "arm64";
                String pricingModel = request.ec2PricingModel != null ? request.ec2PricingModel : "spot";
                String k8sVersion = request.k8sVersion != null ? request.k8sVersion : "1.35";
                if (!arch.equals("arm64") && !arch.equals("x86_64"))
                    return error(400, "InvalidParameterException", "arch must be arm64 or x86_64");
                if (!pricingModel.equals("spot") && !pricingModel.equals("ondemand"))
                    return error(400, "InvalidParameterException", "ec2PricingModel must be spot or ondemand");

                String sshCidr = request.sshCidr;
                if (sshCidr == null || sshCidr.isBlank()) {
                    String sourceIp = (String) ctx.getProperty("sourceIp");
                    if (sourceIp == null || sourceIp.isBlank())
                        return error(400, "InvalidParameterException", "Cannot determine caller IP; provide sshCidr explicitly");
                    sshCidr = sourceIp + "/32";
                }
                if (sshCidr.equals("0.0.0.0/0") || sshCidr.equals("::/0"))
                    return error(400, "InvalidParameterException", "sshCidr must not be open to the world");

                String id = provisioningService.provision(request.clusterName, true, idcUserId, callerArn,
                    arch, pricingModel, k8sVersion,
                    Boolean.TRUE.equals(request.assignElasticIp),
                    request.diskSizeGb != null ? request.diskSizeGb : 20, sshCidr);
                return Response.accepted(Map.of("tenantId", id, "clusterName", request.clusterName, "managed", true)).build();
            } else {
                String id = provisioningService.provision(request.clusterName, false, idcUserId, callerArn,
                    null, null, null, false, 0, null);
                return Response.accepted(Map.of("tenantId", id, "clusterName", request.clusterName, "managed", false)).build();
            }
        } catch (IllegalArgumentException e) {
            return error(400, "InvalidParameterException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Provision tenant error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @GET
    @Path("/{id}")
    public Response getTenant(@PathParam("id") String id, @Context ContainerRequestContext ctx) {
        try {
            String callerArn = (String) ctx.getProperty("callerArn");
            TenantItem item = provisioningService.getState(id);
            if (callerArn != null && item.ownerArn() != null && !callerArn.equals(item.ownerArn()))
                return error(404, "NotFoundException", "Tenant not found: " + id);
            return Response.ok(item).build();
        } catch (IllegalArgumentException e) {
            return error(404, "NotFoundException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Get tenant error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @DELETE
    @Path("/{id}")
    public Response deleteTenant(@PathParam("id") String id, @Context ContainerRequestContext ctx) {
        try {
            String callerArn = (String) ctx.getProperty("callerArn");
            TenantItem item = provisioningService.getState(id);
            if (callerArn != null && item.ownerArn() != null && !callerArn.equals(item.ownerArn()))
                return error(404, "NotFoundException", "Tenant not found: " + id);
            provisioningService.deprovision(id);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return error(404, "NotFoundException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Deprovision tenant error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @POST
    @Path("/{id}/stop")
    public Response stopTenant(@PathParam("id") String id) {
        try {
            provisioningService.stop(id);
            return Response.accepted(Map.of("tenantId", id, "status", "stopping")).build();
        } catch (IllegalArgumentException e) {
            return error(404, "NotFoundException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Stop tenant error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @POST
    @Path("/{id}/resume")
    public Response resumeTenant(@PathParam("id") String id) {
        try {
            provisioningService.resume(id);
            return Response.accepted(Map.of("tenantId", id, "status", "resuming")).build();
        } catch (IllegalArgumentException e) {
            return error(404, "NotFoundException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Resume tenant error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    private Response error(int status, String code, String message) {
        return Response.status(status).entity(Map.of("__type", code, "message", message)).build();
    }
}
