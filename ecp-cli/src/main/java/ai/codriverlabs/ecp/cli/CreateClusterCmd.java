package ai.codriverlabs.ecp.cli;

import ai.codriverlabs.ecp.cli.cluster.UnifiedCreateClusterCommand;
import picocli.CommandLine.Command;

@Command(name = "create-cluster", description = "Create a managed cluster or register a self-managed one")
public class CreateClusterCmd extends UnifiedCreateClusterCommand {}
