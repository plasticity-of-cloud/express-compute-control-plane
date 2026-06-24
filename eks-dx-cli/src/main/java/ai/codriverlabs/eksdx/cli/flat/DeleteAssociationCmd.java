package ai.codriverlabs.eksdx.cli.flat;

import ai.codriverlabs.eksdx.cli.association.DeleteAssociationCommand;
import picocli.CommandLine.Command;

@Command(name = "delete-association", aliases = "delete-pod-identity-association",
    description = "Delete a pod identity association")
public class DeleteAssociationCmd extends DeleteAssociationCommand {}
