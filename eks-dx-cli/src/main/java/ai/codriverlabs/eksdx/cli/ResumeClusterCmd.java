package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.tenant.ResumeTenantCommand;
import picocli.CommandLine.Command;

@Command(name = "resume-cluster", description = "Resume a stopped managed cluster")
public class ResumeClusterCmd extends ResumeTenantCommand {}
