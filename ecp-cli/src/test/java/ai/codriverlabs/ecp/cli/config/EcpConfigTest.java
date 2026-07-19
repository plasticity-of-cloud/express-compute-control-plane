package ai.codriverlabs.ecp.cli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EcpConfigTest {

    @Test
    void getEndpoint_returnsDefault_whenNoConfig() {
        EcpConfig config = new EcpConfig();
        // Without env var or config file, returns default
        assertNotNull(config.getEndpoint());
    }

    @Test
    void getRegion_returnsDefault_whenNoConfig() {
        EcpConfig config = new EcpConfig();
        assertNotNull(config.getRegion());
    }

    @Test
    void configFile_returnsPathInHomeDir() {
        Path path = EcpConfig.configFile();
        assertTrue(path.toString().contains(".express-compute"));
        assertTrue(path.toString().endsWith("config"));
    }
}
