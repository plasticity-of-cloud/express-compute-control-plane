package ai.codriverlabs.ecp.cli;

import ai.codriverlabs.ecp.cli.cluster.UnifiedDeleteClusterCommand;
import picocli.CommandLine.Command;

@Command(name = "delete-cluster", description = "Delete a cluster (managed: full teardown; self-managed: deregister)")
public class DeleteClusterCmd extends UnifiedDeleteClusterCommand {}
