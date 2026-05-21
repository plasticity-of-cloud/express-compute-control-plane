package ai.codriverlabs.eksdx.credential.resource;

import ai.codriverlabs.eksdx.credential.service.AwsCredentialService;
import ai.codriverlabs.eksdx.credential.service.JwksTokenValidationService;
import ai.codriverlabs.eksdx.model.TokenClaims;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.util.Map;

/**
 * Credential exchange endpoint — called by the in-cluster proxy.
 * Wire-compatible with the EKS Pod Identity Agent.
 * No API Gateway auth — token is in the request body, validated by Lambda via JWKS.
 */
@Path("/clusters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EksAuthResource {

    private static final Logger LOG = Logger.getLogger(EksAuthResource.class);

    @Inject JwksTokenValidationService tokenValidationService;
    @Inject AwsCredentialService credentialService;
    @Inject DynamoDbClient dynamoDb;

    @ConfigProperty(name = "eks-dx.associations-table")
    String associationsTable;

    public static class AgentRequest {
        @JsonProperty("token") public String token;
    }

    public static class AgentResponse {
        @JsonProperty("credentials") public CredentialsDto credentials;
        @JsonProperty("assumedRoleUser") public AssumedRoleUserDto assumedRoleUser;
        @JsonProperty("podIdentityAssociation") public AssociationDto podIdentityAssociation;
        @JsonProperty("subject") public SubjectDto subject;
        @JsonProperty("audience") public String audience;
    }

    public static class CredentialsDto {
        @JsonProperty("accessKeyId") public String accessKeyId;
        @JsonProperty("secretAccessKey") public String secretAccessKey;
        @JsonProperty("sessionToken") public String sessionToken;
        @JsonProperty("expiration") public long expiration;
    }

    public static class AssumedRoleUserDto {
        @JsonProperty("arn") public String arn;
        @JsonProperty("assumeRoleId") public String assumeRoleId;
    }

    public static class AssociationDto {
        @JsonProperty("associationArn") public String associationArn;
        @JsonProperty("associationId") public String associationId;
    }

    public static class SubjectDto {
        @JsonProperty("namespace") public String namespace;
        @JsonProperty("serviceAccount") public String serviceAccount;
    }

    @POST
    @Path("/{clusterName}/assets")
    public Response assumeRoleForPodIdentity(
            @PathParam("clusterName") String clusterName,
            AgentRequest request) {
        try {
            if (request == null || request.token == null || request.token.isEmpty())
                return error(400, "InvalidParameterException", "token is required");

            TokenClaims claims = tokenValidationService.validateToken(request.token, clusterName);

            GetItemResponse assocResp = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(associationsTable)
                .key(Map.of(
                    "PK", AttributeValue.fromS("CLUSTER#" + clusterName),
                    "SK", AttributeValue.fromS(claims.namespace() + "#" + claims.serviceAccount())))
                .build());

            if (!assocResp.hasItem() || assocResp.item().isEmpty() || !assocResp.item().containsKey("roleArn"))
                return error(404, "NotFoundException", "No pod identity association found");

            String roleArn = assocResp.item().get("roleArn").s();
            String associationId = assocResp.item().containsKey("associationId")
                ? assocResp.item().get("associationId").s() : "";

            String sessionName = claims.namespace() + "-" + claims.serviceAccount();
            Credentials creds = credentialService.assumeRole(roleArn, sessionName, clusterName, claims.sessionTags());

            AgentResponse resp = new AgentResponse();
            resp.credentials = new CredentialsDto();
            resp.credentials.accessKeyId = creds.accessKeyId();
            resp.credentials.secretAccessKey = creds.secretAccessKey();
            resp.credentials.sessionToken = creds.sessionToken();
            resp.credentials.expiration = creds.expiration().getEpochSecond();
            resp.assumedRoleUser = new AssumedRoleUserDto();
            resp.assumedRoleUser.arn = roleArn;
            resp.assumedRoleUser.assumeRoleId = sessionName;
            resp.podIdentityAssociation = new AssociationDto();
            resp.podIdentityAssociation.associationArn = roleArn;
            resp.podIdentityAssociation.associationId = associationId;
            resp.subject = new SubjectDto();
            resp.subject.namespace = claims.namespace();
            resp.subject.serviceAccount = claims.serviceAccount();
            resp.audience = "pods.eks.amazonaws.com";

            return Response.ok(resp).build();
        } catch (SecurityException e) {
            return error(403, "AccessDeniedException", e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(400, "InvalidParameterException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Auth error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    private Response error(int status, String code, String message) {
        return Response.status(status).entity(Map.of("__type", code, "message", message)).build();
    }
}
