package ai.codriverlabs.ecp.cli;

import ai.codriverlabs.ecp.cli.cluster.DescribeClusterCommand;
import picocli.CommandLine.Command;

@Command(name = "describe-cluster", description = "Show details of a registered cluster")
public class DescribeClusterCmd extends DescribeClusterCommand {}
