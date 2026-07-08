package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.cluster.ConnectClusterCommand;
import picocli.CommandLine.Command;

@Command(name = "connect-cluster", description = "Show SSH connection details for a managed cluster")
public class ConnectClusterCmd extends ConnectClusterCommand {}
