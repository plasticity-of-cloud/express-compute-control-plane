package ai.codriverlabs.eksdx.cli.flat;

import ai.codriverlabs.eksdx.cli.cluster.CreateClusterCommand;
import picocli.CommandLine.Command;

@Command(name = "register-cluster", description = "Register an external cluster with EKS-DX (OIDC/JWKS)")
public class RegisterClusterCmd extends CreateClusterCommand {}
