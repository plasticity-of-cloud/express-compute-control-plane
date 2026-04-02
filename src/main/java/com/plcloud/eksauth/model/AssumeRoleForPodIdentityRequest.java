package com.plcloud.eksauth.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AssumeRoleForPodIdentityRequest {
    
    @JsonProperty("ClusterName")
    private String clusterName;
    
    @JsonProperty("Token")
    private String token;
    
    public AssumeRoleForPodIdentityRequest() {}
    
    public String getClusterName() {
        return clusterName;
    }
    
    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }
    
    public String getToken() {
        return token;
    }
    
    public void setToken(String token) {
        this.token = token;
    }
}
