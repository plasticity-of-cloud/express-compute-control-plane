package ai.codriverlabs.ecp.cli;

import ai.codriverlabs.ecp.cli.cluster.ListClustersCommand;
import picocli.CommandLine.Command;

@Command(name = "list-clusters", description = "List all registered clusters")
public class ListClustersCmd extends ListClustersCommand {}
