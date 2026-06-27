package ai.codriverlabs.eksdx.cli.tenant;

import ai.codriverlabs.eksdx.cli.config.EksDxConfig;
import ai.codriverlabs.eksdx.cli.util.EksDxApiClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "tenant", description = "Stop a tenant cluster (EC2 instance stopped, EBS preserved)")
public class StopTenantCommand implements Runnable {

    @Inject EksDxApiClient apiClient;

    @Parameters(index = "0", description = "Tenant ID") String tenantId;

    @Override
    public void run() {
        try {
            EksDxConfig config = new EksDxConfig();
            String tenantApiUrl = config.getProvisioningUrl();
            if (tenantApiUrl == null) {
                System.err.println("Error: provisioning URL not configured. Set EKS_DX_PROVISIONING_URL or run 'eks-dx configure'.");
                System.exit(1);
            }
            int status = apiClient.postStatusOnUrl(tenantApiUrl, "/tenants/" + tenantId + "/stop", "");
            if (status == 404) {
                System.err.printf("Tenant \"%s\" not found%n", tenantId);
                System.exit(1);
            }
            if (status != 202 && status != 200) {
                System.err.printf("Unexpected status %d stopping tenant%n", status);
                System.exit(1);
            }
            System.out.printf("Stopping tenant \"%s\" — instance will be available again with 'eks-dx resume tenant %s'%n",
                tenantId, tenantId);
        } catch (Exception e) {
            System.err.printf("Failed to stop tenant: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
