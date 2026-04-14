package com.plcloud.eksauth.model;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;
import com.fasterxml.jackson.annotation.JsonProperty;

@Group("eks.amazonaws.com")
@Version("v1")
@Kind("PodIdentityAssociation")
@Plural("podidentityassociations")
public class PodIdentityAssociation extends CustomResource<PodIdentityAssociationSpec, Void> implements Namespaced {
}
