package cloud.plasticity.eksauth.resource;

import cloud.plasticity.eksauth.service.TokenValidationService;
import cloud.plasticity.eksauth.service.PodIdentityAssociationService;
import cloud.plasticity.eksauth.service.AwsCredentialService;
import software.amazon.awssdk.services.sts.model.Credentials;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * AWS-API-compatible endpoint that the EKS Pod Identity Agent calls via its
 * {@code --endpoint} flag. Matches the real EKS Auth Service wire format so
 * the agent's AWS SDK client works unmodified.
 *
 * <p>Real API: {@code POST /clusters/{clusterName}/assets}
 * <br>Request body: {@code {"token":"<jwt>"}}
 * <br>Response body: camelCase JSON matching the eksauth Smithy model.
 */
@Path("/clusters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EksAuthAgentResource {

    private static final Logger LOG = Logger.getLogger(EksAuthAgentResource.class);

    @Inject TokenValidationService tokenValidationService;
    @Inject PodIdentityAssociationService podIdentityAssociationService;
    @Inject AwsCredentialService awsCredentialService;

    // --- request / response DTOs matching the AWS wire format ---

    public static class AgentRequest {
        @JsonProperty("token") public String token;
    }

    public static class AgentResponse {
        @JsonProperty("credentials")              public CredentialsDto credentials;
        @JsonProperty("assumedRoleUser")           public AssumedRoleUserDto assumedRoleUser;
        @JsonProperty("podIdentityAssociation")    public AssociationDto podIdentityAssociation;
        @JsonProperty("subject")                   public SubjectDto subject;
        @JsonProperty("audience")                  public String audience;
    }

    public static class CredentialsDto {
        @JsonProperty("accessKeyId")     public String accessKeyId;
        @JsonProperty("secretAccessKey") public String secretAccessKey;
        @JsonProperty("sessionToken")    public String sessionToken;
        @JsonProperty("expiration")      public long expiration; // epoch seconds
    }

    public static class AssumedRoleUserDto {
        @JsonProperty("arn")           public String arn;
        @JsonProperty("assumeRoleId")  public String assumeRoleId;
    }

    public static class AssociationDto {
        @JsonProperty("associationArn") public String associationArn;
        @JsonProperty("associationId")  public String associationId;
    }

    public static class SubjectDto {
        @JsonProperty("namespace")      public String namespace;
        @JsonProperty("serviceAccount") public String serviceAccount;
    }

    // --- endpoint ---

    @POST
    @Path("/{clusterName}/assets")
    public Response assumeRoleForPodIdentity(
            @PathParam("clusterName") String clusterName,
            AgentRequest request) {
        try {
            if (request == null || request.token == null || request.token.isEmpty()) {
                return error(400, "InvalidParameterException", "token is required");
            }

            LOG.infof("Agent API: AssumeRoleForPodIdentity cluster=%s", clusterName);

            TokenValidationService.TokenClaims claims =
                    tokenValidationService.validateToken(request.token, clusterName);

            String roleArn = podIdentityAssociationService.getRoleArnForServiceAccount(
                    clusterName, claims.getNamespace(), claims.getServiceAccount());

            String sessionName = awsCredentialService.generateSessionName(
                    claims.getNamespace(), claims.getServiceAccount());

            Credentials creds = awsCredentialService.assumeRole(
                    roleArn, sessionName, clusterName, claims.getSessionTags());

            AgentResponse resp = new AgentResponse();

            resp.credentials = new CredentialsDto();
            resp.credentials.accessKeyId     = creds.accessKeyId();
            resp.credentials.secretAccessKey  = creds.secretAccessKey();
            resp.credentials.sessionToken     = creds.sessionToken();
            resp.credentials.expiration       = creds.expiration().getEpochSecond();

            resp.assumedRoleUser = new AssumedRoleUserDto();
            resp.assumedRoleUser.arn          = roleArn;
            resp.assumedRoleUser.assumeRoleId = sessionName;

            resp.podIdentityAssociation = new AssociationDto();
            resp.podIdentityAssociation.associationArn = roleArn;
            resp.podIdentityAssociation.associationId  = podIdentityAssociationService
                    .generateAssociationId(clusterName, claims.getNamespace(), claims.getServiceAccount());

            resp.subject = new SubjectDto();
            resp.subject.namespace      = claims.getNamespace();
            resp.subject.serviceAccount = claims.getServiceAccount();

            resp.audience = TokenValidationService.EKS_POD_IDENTITY_AUDIENCE;

            return Response.ok(resp).build();

        } catch (SecurityException e) {
            return error(403, "AccessDeniedException", e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(400, "InvalidParameterException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Agent API error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    private Response error(int status, String code, String message) {
        return Response.status(status)
                .entity(Map.of("__type", code, "message", message))
                .build();
    }
}
