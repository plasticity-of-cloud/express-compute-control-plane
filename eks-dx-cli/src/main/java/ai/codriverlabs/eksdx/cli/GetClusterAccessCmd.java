package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.cluster.GetClusterAccessCommand;
import picocli.CommandLine.Command;

@Command(name = "get-cluster-access", description = "Show SSH connection details for a managed cluster")
public class GetClusterAccessCmd extends GetClusterAccessCommand {}
