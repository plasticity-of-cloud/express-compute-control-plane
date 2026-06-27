package ai.codriverlabs.eksdx.cli.tenant;

import ai.codriverlabs.eksdx.cli.config.EksDxConfig;
import ai.codriverlabs.eksdx.cli.util.EksDxApiClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "tenant", description = "Resume a stopped tenant cluster")
public class ResumeTenantCommand implements Runnable {

    @Inject EksDxApiClient apiClient;

    @Parameters(index = "0", description = "Tenant ID") String tenantId;

    @Option(names = "--wait", description = "Wait until cluster is reachable", defaultValue = "true")
    boolean wait;

    @Override
    public void run() {
        try {
            EksDxConfig config = new EksDxConfig();
            String tenantApiUrl = config.getProvisioningUrl();
            if (tenantApiUrl == null) {
                System.err.println("Error: provisioning URL not configured. Set EKS_DX_PROVISIONING_URL or run 'eks-dx configure'.");
                System.exit(1);
            }
            int status = apiClient.postStatusOnUrl(tenantApiUrl, "/tenants/" + tenantId + "/resume", "");
            if (status == 404) {
                System.err.printf("Tenant \"%s\" not found%n", tenantId);
                System.exit(1);
            }
            if (status != 202 && status != 200) {
                System.err.printf("Unexpected status %d resuming tenant%n", status);
                System.exit(1);
            }
            System.out.printf("Resuming tenant \"%s\"...%n", tenantId);

            if (!wait) return;

            // Poll until state is "running"
            long deadline = System.currentTimeMillis() + 300_000;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(5_000);
                int getStatus = apiClient.getStatusOnUrl(tenantApiUrl, "/tenants/" + tenantId);
                if (getStatus == 200) {
                    // Check state from body — use a simple re-fetch
                    String body = apiClient.getBodyOnUrl(tenantApiUrl, "/tenants/" + tenantId);
                    if (body != null && (body.contains("\"ready\"") || body.contains("\"running\""))) {
                        System.out.printf("✓ Tenant \"%s\" running%n", tenantId);
                        return;
                    }
                }
            }
            System.err.println("Timeout waiting for tenant to resume.");
            System.exit(1);
        } catch (Exception e) {
            System.err.printf("Failed to resume tenant: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
