package com.plcloud.eksauth.service;

import software.amazon.awssdk.services.sts.StsClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class StsClientProducer {
    
    @Produces
    @ApplicationScoped
    public StsClient createStsClient() {
        return StsClient.builder().build();
    }
}
