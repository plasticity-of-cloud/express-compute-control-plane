package ai.codriverlabs.ecp.cli;

import ai.codriverlabs.ecp.cli.association.CreateAssociationCommand;
import picocli.CommandLine.Command;

@Command(name = "create-association", aliases = "create-pod-identity-association",
    description = "Create a workload identity")
public class CreateAssociationCmd extends CreateAssociationCommand {}
