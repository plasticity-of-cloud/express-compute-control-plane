package com.plcloud.eksauth.service;

import com.plcloud.eksauth.model.PodIdentityAssociation;
import com.plcloud.eksauth.model.PodIdentityAssociationSpec;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.MockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class PodIdentityAssociationServiceTest {
    @MockServer
    KubernetesClient kubernetesClient;

    PodIdentityAssociationService service;

    @BeforeEach
    void setup() {
        service = new PodIdentityAssociationService();
        service.setKubernetesClient(kubernetesClient);
    }

    @Test
    void testGetRoleArnFromCrd() {
        // Create a test CRD
        PodIdentityAssociation crd = new PodIdentityAssociation();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName("test-cluster-test-sa");
        metadata.setNamespace("default");
        crd.setMetadata(metadata);

        PodIdentityAssociationSpec spec = new PodIdentityAssociationSpec(
            "test-cluster",
            "default",
            "test-sa",
            "arn:aws:iam::123456789012:role/test-role"
        );
        crd.setSpec(spec);

        kubernetesClient.customResource(PodIdentityAssociation.class)
            .inNamespace("default")
            .create(crd);

        // Test retrieval
        String roleArn = service.getRoleArnForServiceAccount("test-cluster", "default", "test-sa");
        assertNotNull(roleArn);
        assertEquals("arn:aws:iam::123456789012:role/test-role", roleArn);
    }

    @Test
    void testGetRoleArnFallbackToDefault() {
        // When CRD doesn't exist, should return default
        String roleArn = service.getRoleArnForServiceAccount("test-cluster", "default", "nonexistent");
        assertNotNull(roleArn);
        assertEquals("arn:aws:iam::123456789012:role/eks-pod-identity-default-nonexistent", roleArn);
    }
}
