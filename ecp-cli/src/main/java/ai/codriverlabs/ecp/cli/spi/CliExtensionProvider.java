package ai.codriverlabs.ecp.cli.spi;

import java.util.List;

/**
 * SPI for extending the ecp CLI with additional commands.
 *
 * <p>Implementations are discovered via CDI at startup and their commands
 * are registered as subcommands of the top-level {@code ecp} command.
 *
 * <p>Each provider returns a list of Picocli {@code @Command}-annotated classes.
 * These classes must:
 * <ul>
 *   <li>Be annotated with {@code @Command(name = "verb-noun", ...)} using flat CLI style</li>
 *   <li>Implement {@code Runnable} or {@code Callable<Integer>}</li>
 *   <li>Be CDI-managed beans (annotated with a scope or registered as beans)</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @ApplicationScoped
 * public class EksProExtensionProvider implements CliExtensionProvider {
 *     @Override
 *     public List<Class<?>> commands() {
 *         return List.of(
 *             RegisterEksClusterCmd.class,
 *             RegisterEcsClusterCmd.class
 *         );
 *     }
 * }
 * }</pre>
 */
public interface CliExtensionProvider {

    /**
     * Returns the list of command classes to register as subcommands.
     * Each class must be annotated with Picocli's {@code @Command}.
     */
    List<Class<?>> commands();
}
