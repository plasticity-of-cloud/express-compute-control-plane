package ai.codriverlabs.eksdx.cli.flat;

import ai.codriverlabs.eksdx.cli.cluster.DeleteClusterCommand;
import picocli.CommandLine.Command;

@Command(name = "deregister-cluster", description = "Remove a cluster registration from EKS-DX")
public class DeregisterClusterCmd extends DeleteClusterCommand {}
