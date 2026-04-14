package com.plcloud.eksauth.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PodIdentityAssociationSpec {
    @JsonProperty("clusterName")
    private String clusterName;

    @JsonProperty("namespace")
    private String namespace;

    @JsonProperty("serviceAccount")
    private String serviceAccount;

    @JsonProperty("roleArn")
    private String roleArn;

    public PodIdentityAssociationSpec() {
    }

    public PodIdentityAssociationSpec(String clusterName, String namespace, String serviceAccount, String roleArn) {
        this.clusterName = clusterName;
        this.namespace = namespace;
        this.serviceAccount = serviceAccount;
        this.roleArn = roleArn;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getServiceAccount() {
        return serviceAccount;
    }

    public void setServiceAccount(String serviceAccount) {
        this.serviceAccount = serviceAccount;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }
}
