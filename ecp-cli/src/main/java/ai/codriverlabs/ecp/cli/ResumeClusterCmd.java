package ai.codriverlabs.ecp.cli;

import ai.codriverlabs.ecp.cli.tenant.ResumeTenantCommand;
import picocli.CommandLine.Command;

@Command(name = "resume-cluster", description = "Resume a stopped managed cluster")
public class ResumeClusterCmd extends ResumeTenantCommand {}
