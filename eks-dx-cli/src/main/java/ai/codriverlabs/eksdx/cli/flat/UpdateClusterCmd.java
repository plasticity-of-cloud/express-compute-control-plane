package ai.codriverlabs.eksdx.cli.flat;

import ai.codriverlabs.eksdx.cli.cluster.UpdateClusterCommand;
import picocli.CommandLine.Command;

@Command(name = "update-cluster", description = "Update cluster configuration (e.g. refresh JWKS)")
public class UpdateClusterCmd extends UpdateClusterCommand {}
