package ai.codriverlabs.ecp.cli.config;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "configure", description = "Configure EKS-DX CLI endpoint and region")
public class ConfigureCommand implements Runnable {

    @Option(names = "--endpoint", description = "EKS-DX API endpoint URL")
    String endpoint;

    @Option(names = "--region", description = "AWS region")
    String region;

    @Override
    public void run() {
        if (endpoint == null && region == null) {
            // Show current config
            EcpConfig config = new EcpConfig();
            System.out.printf("Endpoint: %s%n", config.getEndpoint());
            System.out.printf("Region:   %s%n", config.getRegion());
            System.out.printf("Config:   %s%n", EcpConfig.configFile());
            return;
        }

        try {
            new EcpConfig().save(endpoint, region);
            System.out.println("✓ Configuration saved to " + EcpConfig.configFile());
            if (endpoint != null) System.out.printf("  Endpoint: %s%n", endpoint);
            if (region != null) System.out.printf("  Region:   %s%n", region);
        } catch (Exception e) {
            System.err.printf("Failed to save configuration: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
