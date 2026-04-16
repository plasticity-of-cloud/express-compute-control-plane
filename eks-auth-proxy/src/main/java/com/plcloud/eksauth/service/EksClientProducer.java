package com.plcloud.eksauth.service;

import software.amazon.awssdk.services.eks.EksClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class EksClientProducer {

    @Produces
    @ApplicationScoped
    public EksClient createEksClient() {
        return EksClient.builder().build();
    }
}
