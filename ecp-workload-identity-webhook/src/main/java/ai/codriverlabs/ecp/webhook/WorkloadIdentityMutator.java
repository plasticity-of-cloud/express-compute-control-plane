package ai.codriverlabs.ecp.webhook;

import io.fabric8.kubernetes.api.model.*;
import io.javaoperatorsdk.webhook.admission.AdmissionController;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutates pods to inject EKS Workload Identity Agent env vars and projected token volume,
 * mirroring what the real EKS Workload Identity Agent does on managed EKS nodes.
 */
@ApplicationScoped
public class WorkloadIdentityMutator {

    private static final Logger LOG = Logger.getLogger(WorkloadIdentityMutator.class);

    static final String CREDENTIALS_URI = "http://169.254.170.23/v1/credentials";
    static final String TOKEN_FILE_PATH = "/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token";
    static final String TOKEN_VOLUME_NAME = "eks-pod-identity-token";
    static final String TOKEN_MOUNT_PATH = "/var/run/secrets/pods.eks.amazonaws.com/serviceaccount";
    static final String TOKEN_AUDIENCE = "pods.eks.amazonaws.com";

    @Inject
    LambdaAssociationLookup associationLookup;

    public AdmissionController<Pod> controller() {
        return new AdmissionController<>((pod, operation) -> {
            String namespace = pod.getMetadata().getNamespace();
            String serviceAccount = pod.getSpec().getServiceAccountName();
            if (serviceAccount == null || serviceAccount.isBlank()) serviceAccount = "default";

            if (!associationLookup.hasAssociation(namespace, serviceAccount)) {
                LOG.debugf("No Workload Identity association for %s/%s, skipping", namespace, serviceAccount);
                return pod;
            }

            LOG.infof("Injecting Workload Identity env vars for %s/%s", namespace, serviceAccount);
            injectEnvVars(pod);
            injectTokenVolume(pod);
            return pod;
        });
    }

    private void injectEnvVars(Pod pod) {
        for (Container container : pod.getSpec().getContainers()) {
            List<EnvVar> env = container.getEnv();
            if (env == null) { env = new ArrayList<>(); container.setEnv(env); }

            if (env.stream().noneMatch(e -> "AWS_CONTAINER_CREDENTIALS_FULL_URI".equals(e.getName())))
                env.add(new EnvVarBuilder().withName("AWS_CONTAINER_CREDENTIALS_FULL_URI").withValue(CREDENTIALS_URI).build());

            if (env.stream().noneMatch(e -> "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE".equals(e.getName())))
                env.add(new EnvVarBuilder().withName("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE").withValue(TOKEN_FILE_PATH).build());

            List<VolumeMount> mounts = container.getVolumeMounts();
            if (mounts == null) { mounts = new ArrayList<>(); container.setVolumeMounts(mounts); }
            if (mounts.stream().noneMatch(m -> TOKEN_VOLUME_NAME.equals(m.getName())))
                mounts.add(new VolumeMountBuilder().withName(TOKEN_VOLUME_NAME).withMountPath(TOKEN_MOUNT_PATH).withReadOnly(true).build());
        }
    }

    private void injectTokenVolume(Pod pod) {
        List<Volume> volumes = pod.getSpec().getVolumes();
        if (volumes == null) { volumes = new ArrayList<>(); pod.getSpec().setVolumes(volumes); }
        if (volumes.stream().anyMatch(v -> TOKEN_VOLUME_NAME.equals(v.getName()))) return;

        volumes.add(new VolumeBuilder()
            .withName(TOKEN_VOLUME_NAME)
            .withNewProjected()
                .withSources(new VolumeProjectionBuilder()
                    .withNewServiceAccountToken()
                        .withAudience(TOKEN_AUDIENCE)
                        .withExpirationSeconds(86400L)
                        .withPath("eks-pod-identity-token")
                    .endServiceAccountToken()
                    .build())
            .endProjected()
            .build());
    }
}
