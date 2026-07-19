package ai.codriverlabs.ecp.cli;

import ai.codriverlabs.ecp.cli.tenant.StopTenantCommand;
import picocli.CommandLine.Command;

@Command(name = "stop-cluster", description = "Stop a managed cluster (EC2 stopped, EBS preserved)")
public class StopClusterCmd extends StopTenantCommand {}
