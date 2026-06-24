package ai.codriverlabs.eksdx.cli.flat;

import ai.codriverlabs.eksdx.cli.association.ListAssociationsCommand;
import picocli.CommandLine.Command;

@Command(name = "list-associations", aliases = "list-pod-identity-associations",
    description = "List pod identity associations for a cluster")
public class ListAssociationsCmd extends ListAssociationsCommand {}
