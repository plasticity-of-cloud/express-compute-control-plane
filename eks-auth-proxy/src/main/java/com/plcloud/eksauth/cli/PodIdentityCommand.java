package com.plcloud.eksauth.cli;

import picocli.commandline.Command;
import picocli.commandline.CommandLine;

@Command(
    name = "eks-pod-identity-association",
    description = "Manage EKS Pod Identity Associations",
    subcommands = {
        CreateCommand.class,
        DeleteCommand.class,
        DescribeCommand.class,
        ListCommand.class
    },
    mixinStandardHelpOptions = true,
    version = "1.0.0"
)
public class PodIdentityCommand implements Runnable {
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
