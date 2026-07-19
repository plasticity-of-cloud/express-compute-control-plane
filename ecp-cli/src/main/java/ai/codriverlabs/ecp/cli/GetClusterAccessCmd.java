package ai.codriverlabs.ecp.cli;

import ai.codriverlabs.ecp.cli.cluster.GetClusterAccessCommand;
import picocli.CommandLine.Command;

@Command(name = "get-cluster-access", description = "Show SSH connection details for a managed cluster")
public class GetClusterAccessCmd extends GetClusterAccessCommand {}
