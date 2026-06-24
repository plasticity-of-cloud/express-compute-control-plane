package ai.codriverlabs.eksdx.cli.flat;

import ai.codriverlabs.eksdx.cli.association.DescribeAssociationCommand;
import picocli.CommandLine.Command;

@Command(name = "describe-association", aliases = "describe-pod-identity-association",
    description = "Show details of a pod identity association")
public class DescribeAssociationCmd extends DescribeAssociationCommand {}
