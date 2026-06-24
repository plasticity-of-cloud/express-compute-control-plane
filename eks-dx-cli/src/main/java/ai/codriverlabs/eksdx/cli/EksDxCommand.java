package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.config.ConfigureCommand;
import ai.codriverlabs.eksdx.cli.flat.*;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine.Command;

@TopCommand
@Command(name = "eks-dx", mixinStandardHelpOptions = true,
    description = "EKS-DX — Pod Identity for k3s, microk8s, and EKS-D clusters",
    subcommands = {
        ConfigureCommand.class,
        RegisterClusterCmd.class,
        DeregisterClusterCmd.class,
        DescribeClusterCmd.class,
        ListClustersCmd.class,
        UpdateClusterCmd.class,
        CreateAssociationCmd.class,
        DeleteAssociationCmd.class,
        DescribeAssociationCmd.class,
        ListAssociationsCmd.class,
        CreateTenantCmd.class,
        DeleteTenantCmd.class,
        StopTenantCmd.class,
        ResumeTenantCmd.class,
    })
public class EksDxCommand {}
