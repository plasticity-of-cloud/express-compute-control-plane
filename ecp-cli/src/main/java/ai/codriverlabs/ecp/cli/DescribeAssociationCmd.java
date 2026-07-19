package ai.codriverlabs.ecp.cli;

import ai.codriverlabs.ecp.cli.association.DescribeAssociationCommand;
import picocli.CommandLine.Command;

@Command(name = "describe-association", aliases = "describe-pod-identity-association",
    description = "Show details of a workload identity")
public class DescribeAssociationCmd extends DescribeAssociationCommand {}
