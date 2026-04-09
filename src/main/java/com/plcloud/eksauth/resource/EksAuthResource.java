package com.plcloud.eksauth.resource;

import com.plcloud.eksauth.model.AssumeRoleForPodIdentityRequest;
import com.plcloud.eksauth.model.AssumeRoleForPodIdentityResponse;
import com.plcloud.eksauth.service.TokenValidationService;
import com.plcloud.eksauth.service.PodIdentityAssociationService;
import com.plcloud.eksauth.service.AwsCredentialService;
import software.amazon.awssdk.services.sts.model.Credentials;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import java.util.Map;
import java.util.Optional;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EksAuthResource {
    
    private static final Logger LOG = Logger.getLogger(EksAuthResource.class);
    
    @Inject
    TokenValidationService tokenValidationService;
    
    @Inject
    PodIdentityAssociationService podIdentityAssociationService;
    
    @Inject
    AwsCredentialService awsCredentialService;
    
    @POST
    @Counted(value = "eks_auth_requests_total", description = "Total number of EKS Auth requests")
    @Timed(value = "eks_auth_request_duration", description = "EKS Auth request duration")
    public Response assumeRoleForPodIdentity(AssumeRoleForPodIdentityRequest request) {
        try {
            // Validate request
            if (request == null || request.getToken() == null || request.getToken().isEmpty()) {
                throw new IllegalArgumentException("Token is required");
            }
            if (request.getClusterName() == null || request.getClusterName().isEmpty()) {
                throw new IllegalArgumentException("ClusterName is required");
            }
            
            LOG.infof("Processing AssumeRoleForPodIdentity request for cluster: %s", request.getClusterName());
            
            // 1. Validate service account token via Kubernetes TokenReview
            TokenValidationService.TokenClaims claims = tokenValidationService.validateToken(
                request.getToken(), request.getClusterName());
            
            // 2. Look up pod identity association
            String roleArn = podIdentityAssociationService.getRoleArnForServiceAccount(
                request.getClusterName(), claims.getNamespace(), claims.getServiceAccount());
            
            // 3. Generate session name
            String sessionName = awsCredentialService.generateSessionName(
                claims.getNamespace(), claims.getServiceAccount());
            
            // 4. Assume the role via STS with session tags
            Credentials awsCredentials = awsCredentialService.assumeRole(
                roleArn, sessionName, request.getClusterName(), claims.getSessionTags());
            
            // 5. Build response
            AssumeRoleForPodIdentityResponse response = new AssumeRoleForPodIdentityResponse();
            
            // Set credentials
            AssumeRoleForPodIdentityResponse.Credentials credentials = new AssumeRoleForPodIdentityResponse.Credentials();
            credentials.setAccessKeyId(awsCredentials.accessKeyId());
            credentials.setSecretAccessKey(awsCredentials.secretAccessKey());
            credentials.setSessionToken(awsCredentials.sessionToken());
            credentials.setExpiration(awsCredentials.expiration());
            response.setCredentials(credentials);
            
            // Set assumed role user
            AssumeRoleForPodIdentityResponse.AssumedRoleUser assumedRoleUser = new AssumeRoleForPodIdentityResponse.AssumedRoleUser();
            assumedRoleUser.setArn(roleArn);
            assumedRoleUser.setAssumeRoleId(sessionName);
            response.setAssumedRoleUser(assumedRoleUser);
            
            // Set pod identity association
            AssumeRoleForPodIdentityResponse.PodIdentityAssociation association = new AssumeRoleForPodIdentityResponse.PodIdentityAssociation();
            association.setArn(roleArn);
            association.setAssociationId(podIdentityAssociationService.generateAssociationId(
                request.getClusterName(), claims.getNamespace(), claims.getServiceAccount()));
            response.setPodIdentityAssociation(association);
            
            // Set subject
            AssumeRoleForPodIdentityResponse.Subject subject = new AssumeRoleForPodIdentityResponse.Subject();
            subject.setNamespace(claims.getNamespace());
            subject.setServiceAccount(claims.getServiceAccount());
            response.setSubject(subject);
            
            // Set audience (EKS Pod Identity standard)
            response.setAudience(TokenValidationService.EKS_POD_IDENTITY_AUDIENCE);
            
            LOG.infof("Successfully processed request for %s/%s -> %s", 
                claims.getNamespace(), claims.getServiceAccount(), roleArn);
            
            return Response.ok(response).build();
            
        } catch (IllegalArgumentException e) {
            LOG.errorf("Invalid request: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "InvalidRequestException", "message", e.getMessage()))
                .build();
        } catch (SecurityException e) {
            LOG.errorf("Access denied: %s", e.getMessage());
            return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("error", "AccessDeniedException", "message", e.getMessage()))
                .build();
        } catch (Exception e) {
            LOG.errorf("Internal error: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", "InternalServerException", "message", "Internal server error"))
                .build();
        }
    }
}
