package ai.codriverlabs.eksdx.cli.flat;

import ai.codriverlabs.eksdx.cli.cluster.ListClustersCommand;
import picocli.CommandLine.Command;

@Command(name = "list-clusters", description = "List all registered clusters")
public class ListClustersCmd extends ListClustersCommand {}
