package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.tenant.StopTenantCommand;
import picocli.CommandLine.Command;

@Command(name = "stop", subcommands = { StopTenantCommand.class })
public class StopCommand {}
