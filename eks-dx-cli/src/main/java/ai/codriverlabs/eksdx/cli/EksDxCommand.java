package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.config.ConfigureCommand;
import ai.codriverlabs.eksdx.cli.GetClusterAccessCmd;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine.Command;

@TopCommand
@Command(name = "eks-dx", mixinStandardHelpOptions = true,
    description = "EKS-DX — Pod Identity for k3s, microk8s, and EKS-D clusters",
    subcommands = {
        ConfigureCommand.class,
        CreateClusterCmd.class,
        DeleteClusterCmd.class,
        StopClusterCmd.class,
        ResumeClusterCmd.class,
        DescribeClusterCmd.class,
        ListClustersCmd.class,
        UpdateClusterCmd.class,
        CreateAssociationCmd.class,
        DeleteAssociationCmd.class,
        DescribeAssociationCmd.class,
        ListAssociationsCmd.class,
        GetClusterAccessCmd.class,
    })
public class EksDxCommand {}
