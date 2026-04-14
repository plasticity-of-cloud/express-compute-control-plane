package com.plcloud.eksauth.cli;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import picocli.commandline.CommandLine;
import jakarta.inject.Inject;

@QuarkusMain
public class CliMain implements QuarkusApplication {
    @Inject
    CommandLine.IFactory factory;

    @Override
    public int run(String... args) throws Exception {
        return new CommandLine(PodIdentityCommand.class, factory)
            .execute(args);
    }
}
