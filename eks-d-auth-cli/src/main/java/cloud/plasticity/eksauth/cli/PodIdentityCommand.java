package cloud.plasticity.eksauth.cli;

import picocli.CommandLine.Command;

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
        // help is printed by picocli via mixinStandardHelpOptions
    }
}
