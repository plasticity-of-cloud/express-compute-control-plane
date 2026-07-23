package ai.codriverlabs.ecp.tenant.service;

import ai.codriverlabs.ecp.tenant.model.TenantProgress;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Simulates provisioning progress for SSE streaming tests.
 * Activated by setting ECP_DRY_RUN=true.
 */
@ApplicationScoped
public class DryRunProvisioningService {

    @ConfigProperty(name = "express-compute.dry-run", defaultValue = "false")
    boolean dryRun;

    public boolean isEnabled() {
        return dryRun;
    }

    private static final List<TenantProgress> SIMULATED_EVENTS = List.of(
        new TenantProgress("provisioning", "EC2 instance pending...", 1, null, 0, null, null),
        new TenantProgress("provisioning", "EC2 instance running...", 1, null, 5, null, null),
        new TenantProgress("provisioning", "provisioning_started", 2, "203.0.113.42", 10, null, null),
        new TenantProgress("provisioning", "Preparing etcd volume", 30, "203.0.113.42", 15, null, null),
        new TenantProgress("provisioning", "Configuring IAM authenticator", 35, "203.0.113.42", 20, null, null),
        new TenantProgress("kubeadm-init", "Initialising control plane", 40, "203.0.113.42", 25, null, null),
        new TenantProgress("kubeadm-done", "Control plane ready", 50, "203.0.113.42", 35, null, null),
        new TenantProgress("provisioning", "Installing VPC CNI", 60, "203.0.113.42", 40, null, null),
        new TenantProgress("provisioning", "Installing cloud provider", 65, "203.0.113.42", 45, null, null),
        new TenantProgress("provisioning", "Node ready", 70, "203.0.113.42", 50, null, null),
        new TenantProgress("provisioning", "cert-manager installed", 75, "203.0.113.42", 55, null, null),
        new TenantProgress("provisioning", "EBS CSI installed", 80, "203.0.113.42", 60, null, null),
        new TenantProgress("provisioning", "Karpenter installed", 90, "203.0.113.42", 65, null, null),
        new TenantProgress("provisioning", "CloudWatch installed", 95, "203.0.113.42", 70, null, null),
        new TenantProgress("ready", "Cluster ready", 100, "203.0.113.42", 75, null, "-----BEGIN RSA PRIVATE KEY-----\nDRY-RUN-SIMULATED-KEY\n-----END RSA PRIVATE KEY-----\n")
    );

    public List<TenantProgress> getSimulatedEvents() {
        return SIMULATED_EVENTS;
    }
}
