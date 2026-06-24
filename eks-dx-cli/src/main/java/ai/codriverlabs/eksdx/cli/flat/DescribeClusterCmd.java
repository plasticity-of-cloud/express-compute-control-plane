package ai.codriverlabs.eksdx.cli.flat;

import ai.codriverlabs.eksdx.cli.cluster.DescribeClusterCommand;
import picocli.CommandLine.Command;

@Command(name = "describe-cluster", description = "Show details of a registered cluster")
public class DescribeClusterCmd extends DescribeClusterCommand {}
