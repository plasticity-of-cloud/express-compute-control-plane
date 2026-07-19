package ai.codriverlabs.ecp.cli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ConfigureCommandTest {

    @Test
    void run_showsCurrentConfig_whenNoFlags() {
        ConfigureCommand cmd = new ConfigureCommand();

        PrintStream original = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture));
        try {
            cmd.run();
        } finally {
            System.setOut(original);
        }

        String output = capture.toString();
        assertTrue(output.contains("Endpoint:"));
        assertTrue(output.contains("Region:"));
        assertTrue(output.contains("Config:"));
    }
}
