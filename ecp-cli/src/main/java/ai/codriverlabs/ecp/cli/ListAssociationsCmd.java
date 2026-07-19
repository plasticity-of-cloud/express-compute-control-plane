package ai.codriverlabs.ecp.cli;

import ai.codriverlabs.ecp.cli.association.ListAssociationsCommand;
import picocli.CommandLine.Command;

@Command(name = "list-associations", aliases = "list-workload-identities",
    description = "List workload identitys for a cluster")
public class ListAssociationsCmd extends ListAssociationsCommand {}
