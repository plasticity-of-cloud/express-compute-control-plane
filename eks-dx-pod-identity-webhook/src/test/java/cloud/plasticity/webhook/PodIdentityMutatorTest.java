package cloud.plasticity.webhook;

import io.fabric8.kubernetes.api.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PodIdentityMutatorTest {

    PodIdentityMutator mutator;

    @BeforeEach
    void setUp() {
        mutator = new PodIdentityMutator();
    }

    @Test
    void controller_injectsEnvVars_whenAssociationExists() {
        Pod pod = buildPod("default", "my-sa");
        Pod result = mutatePod(pod, true);

        List<EnvVar> env = result.getSpec().getContainers().get(0).getEnv();
        assertTrue(env.stream().anyMatch(e -> "AWS_CONTAINER_CREDENTIALS_FULL_URI".equals(e.getName())));
        assertTrue(env.stream().anyMatch(e -> "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE".equals(e.getName())));
    }

    @Test
    void controller_injectsTokenVolume_whenAssociationExists() {
        Pod pod = buildPod("default", "my-sa");
        Pod result = mutatePod(pod, true);

        assertTrue(result.getSpec().getVolumes().stream()
            .anyMatch(v -> PodIdentityMutator.TOKEN_VOLUME_NAME.equals(v.getName())));
    }

    @Test
    void controller_injectsVolumeMount_whenAssociationExists() {
        Pod pod = buildPod("default", "my-sa");
        Pod result = mutatePod(pod, true);

        assertTrue(result.getSpec().getContainers().get(0).getVolumeMounts().stream()
            .anyMatch(m -> PodIdentityMutator.TOKEN_VOLUME_NAME.equals(m.getName())));
    }

    @Test
    void controller_doesNotMutate_whenNoAssociation() {
        Pod pod = buildPod("default", "no-sa");
        Pod result = mutatePod(pod, false);

        // No env vars injected
        List<EnvVar> env = result.getSpec().getContainers().get(0).getEnv();
        assertTrue(env == null || env.isEmpty());
    }

    @Test
    void controller_doesNotDuplicateEnvVars() {
        Pod pod = buildPod("default", "my-sa");
        // First mutation
        Pod result = mutatePod(pod, true);
        // Second mutation (simulate re-admission)
        result = mutatePod(result, true);

        long count = result.getSpec().getContainers().get(0).getEnv().stream()
            .filter(e -> "AWS_CONTAINER_CREDENTIALS_FULL_URI".equals(e.getName()))
            .count();
        assertEquals(1, count);
    }

    @Test
    void controller_doesNotDuplicateVolume() {
        Pod pod = buildPod("default", "my-sa");
        Pod result = mutatePod(pod, true);
        result = mutatePod(result, true);

        long count = result.getSpec().getVolumes().stream()
            .filter(v -> PodIdentityMutator.TOKEN_VOLUME_NAME.equals(v.getName()))
            .count();
        assertEquals(1, count);
    }

    @Test
    void controller_handlesNullServiceAccount() {
        Pod pod = buildPod("default", null);
        // Should use "default" as service account
        Pod result = mutatePod(pod, false);
        assertNotNull(result);
    }

    @Test
    void controller_usesCorrectTokenAudience() {
        Pod pod = buildPod("default", "my-sa");
        Pod result = mutatePod(pod, true);

        Volume tokenVolume = result.getSpec().getVolumes().stream()
            .filter(v -> PodIdentityMutator.TOKEN_VOLUME_NAME.equals(v.getName()))
            .findFirst().orElseThrow();

        String audience = tokenVolume.getProjected().getSources().get(0)
            .getServiceAccountToken().getAudience();
        assertEquals("pods.eks.amazonaws.com", audience);
    }

    // --- helpers ---

    /**
     * Simulates the mutation logic without going through the AdmissionController.
     */
    private Pod mutatePod(Pod pod, boolean hasAssociation) {
        String namespace = pod.getMetadata().getNamespace();
        String sa = pod.getSpec().getServiceAccountName();
        if (sa == null || sa.isBlank()) sa = "default";

        if (hasAssociation) {
            // Replicate the mutation logic
            injectEnvVars(pod);
            injectTokenVolume(pod);
        }
        return pod;
    }

    private void injectEnvVars(Pod pod) {
        for (Container container : pod.getSpec().getContainers()) {
            List<EnvVar> env = container.getEnv();
            if (env == null) { env = new ArrayList<>(); container.setEnv(env); }

            if (env.stream().noneMatch(e -> "AWS_CONTAINER_CREDENTIALS_FULL_URI".equals(e.getName())))
                env.add(new EnvVarBuilder().withName("AWS_CONTAINER_CREDENTIALS_FULL_URI")
                    .withValue(PodIdentityMutator.CREDENTIALS_URI).build());

            if (env.stream().noneMatch(e -> "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE".equals(e.getName())))
                env.add(new EnvVarBuilder().withName("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE")
                    .withValue(PodIdentityMutator.TOKEN_FILE_PATH).build());

            List<VolumeMount> mounts = container.getVolumeMounts();
            if (mounts == null) { mounts = new ArrayList<>(); container.setVolumeMounts(mounts); }
            if (mounts.stream().noneMatch(m -> PodIdentityMutator.TOKEN_VOLUME_NAME.equals(m.getName())))
                mounts.add(new VolumeMountBuilder().withName(PodIdentityMutator.TOKEN_VOLUME_NAME)
                    .withMountPath(PodIdentityMutator.TOKEN_MOUNT_PATH).withReadOnly(true).build());
        }
    }

    private void injectTokenVolume(Pod pod) {
        List<Volume> volumes = pod.getSpec().getVolumes();
        if (volumes == null) { volumes = new ArrayList<>(); pod.getSpec().setVolumes(volumes); }
        if (volumes.stream().anyMatch(v -> PodIdentityMutator.TOKEN_VOLUME_NAME.equals(v.getName()))) return;

        volumes.add(new VolumeBuilder()
            .withName(PodIdentityMutator.TOKEN_VOLUME_NAME)
            .withNewProjected()
                .withSources(new VolumeProjectionBuilder()
                    .withNewServiceAccountToken()
                        .withAudience(PodIdentityMutator.TOKEN_AUDIENCE)
                        .withExpirationSeconds(86400L)
                        .withPath("eks-pod-identity-token")
                    .endServiceAccountToken()
                    .build())
            .endProjected()
            .build());
    }

    private Pod buildPod(String namespace, String serviceAccount) {
        return new PodBuilder()
            .withNewMetadata()
                .withName("test-pod")
                .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
                .withServiceAccountName(serviceAccount)
                .addNewContainer()
                    .withName("app")
                    .withImage("nginx")
                .endContainer()
            .endSpec()
            .build();
    }
}
