package ai.codriverlabs.ecp.cli.spi;

import io.quarkus.picocli.runtime.PicocliCommandLineFactory;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import picocli.CommandLine;

/**
 * Custom {@link PicocliCommandLineFactory} that creates the standard CommandLine
 * and then registers commands from all discovered {@link CliExtensionProvider} beans.
 *
 * <p>This replaces the default Quarkus factory via CDI {@code @Alternative} priority,
 * allowing extension modules to contribute commands without modifying the core CLI.
 *
 * <h2>Extension Points</h2>
 * <p>To add commands to the CLI, implement {@link CliExtensionProvider} in your module:
 * <pre>{@code
 * @ApplicationScoped
 * public class MyExtension implements CliExtensionProvider {
 *     @Override
 *     public List<Class<?>> commands() {
 *         return List.of(RegisterEksClusterCmd.class, RegisterEcsClusterCmd.class);
 *     }
 * }
 * }</pre>
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class ExtensibleCommandLineFactory implements PicocliCommandLineFactory {

    @Inject
    CommandLine.IFactory picocliFactory;

    @Inject
    Instance<CliExtensionProvider> extensionProviders;

    @Override
    public CommandLine create() {
        // Build the standard command tree from @TopCommand annotations
        CommandLine commandLine = new CommandLine(
            ai.codriverlabs.ecp.cli.EcpCommand.class, picocliFactory);

        // Register extension commands contributed via SPI
        if (extensionProviders != null && !extensionProviders.isUnsatisfied()) {
            for (CliExtensionProvider provider : extensionProviders) {
                for (Class<?> cmdClass : provider.commands()) {
                    try {
                        Object instance = picocliFactory.create(cmdClass);
                        commandLine.addSubcommand(instance);
                    } catch (Exception e) {
                        System.err.printf("Warning: failed to register extension command %s: %s%n",
                            cmdClass.getSimpleName(), e.getMessage());
                    }
                }
            }
        }

        return commandLine;
    }
}
