package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.tenant.ResumeTenantCommand;
import picocli.CommandLine.Command;

@Command(name = "resume", subcommands = { ResumeTenantCommand.class })
public class ResumeCommand {}
