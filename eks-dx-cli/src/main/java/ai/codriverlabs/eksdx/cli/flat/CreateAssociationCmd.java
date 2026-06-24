package ai.codriverlabs.eksdx.cli.flat;

import ai.codriverlabs.eksdx.cli.association.CreateAssociationCommand;
import picocli.CommandLine.Command;

@Command(name = "create-association", aliases = "create-pod-identity-association",
    description = "Create a pod identity association")
public class CreateAssociationCmd extends CreateAssociationCommand {}
