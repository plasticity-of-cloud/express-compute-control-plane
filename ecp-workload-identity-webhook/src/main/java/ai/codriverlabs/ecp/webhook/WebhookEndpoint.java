package ai.codriverlabs.ecp.webhook;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import io.javaoperatorsdk.webhook.admission.AdmissionController;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/mutate")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WebhookEndpoint {

    private final AdmissionController<Pod> controller;

    @Inject
    public WebhookEndpoint(WorkloadIdentityMutator mutator) {
        this.controller = mutator.controller();
    }

    @POST
    public AdmissionReview mutate(AdmissionReview admissionReview) {
        return controller.handle(admissionReview);
    }
}
