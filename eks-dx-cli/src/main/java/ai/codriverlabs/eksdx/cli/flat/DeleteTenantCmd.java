package ai.codriverlabs.eksdx.cli.flat;

import ai.codriverlabs.eksdx.cli.tenant.DeleteTenantCommand;
import picocli.CommandLine.Command;

@Command(name = "delete-tenant", description = "Deprovision a tenant cluster")
public class DeleteTenantCmd extends DeleteTenantCommand {}
