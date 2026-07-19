package ai.codriverlabs.ecp.cli;

import ai.codriverlabs.ecp.cli.association.DeleteAssociationCommand;
import picocli.CommandLine.Command;

@Command(name = "delete-association", aliases = "delete-pod-identity-association",
    description = "Delete a workload identity")
public class DeleteAssociationCmd extends DeleteAssociationCommand {}
