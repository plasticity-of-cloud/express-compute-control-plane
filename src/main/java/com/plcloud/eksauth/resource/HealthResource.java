package com.plcloud.eksauth.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {
    
    @GET
    @Path("/live")
    public Map<String, String> liveness() {
        return Map.of("status", "UP", "check", "liveness");
    }
    
    @GET
    @Path("/ready")
    public Map<String, String> readiness() {
        return Map.of("status", "UP", "check", "readiness");
    }
}
