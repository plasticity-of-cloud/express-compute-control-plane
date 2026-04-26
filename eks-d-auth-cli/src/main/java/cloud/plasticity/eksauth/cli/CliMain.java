package cloud.plasticity.eksauth.cli;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import picocli.CommandLine;

@QuarkusMain
public class CliMain implements QuarkusApplication {
    @Inject
    CommandLine.IFactory factory;

    @Override
    public int run(String... args) throws Exception {
        return new CommandLine(new PodIdentityCommand(), factory).execute(args);
    }
}
