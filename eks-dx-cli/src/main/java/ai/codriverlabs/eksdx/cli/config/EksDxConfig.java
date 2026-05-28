package ai.codriverlabs.eksdx.cli.config;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Reads/writes ~/.eks-d-xpress/config.
 * Endpoint resolution order: EKS_DX_ENDPOINT env → config file → SSM → default.
 */
public class EksDxConfig {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".eks-d-xpress");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config");
    static final String SSM_PARAM_ENDPOINT = "/eks-d-xpress/control-plane/api/endpoint";
    static final String SSM_PARAM_STREAM_URL = "/eks-d-xpress/control-plane/api/stream-url";

    private final Properties props = new Properties();

    public EksDxConfig() {
        load();
    }

    /** ~/.eks-d-xpress/tenants/{region}/{tenantId}.pem */
    public Path tenantSshKeyPath(String region, String tenantId) {
        return CONFIG_DIR.resolve("tenants").resolve(region).resolve(tenantId + ".pem");
    }

    public String getEndpoint() {
        String env = System.getenv("EKS_DX_ENDPOINT");
        if (env != null && !env.isBlank()) return env;
        String fromFile = props.getProperty("endpoint");
        if (fromFile != null && !fromFile.isBlank()) return fromFile;
        String fromSsm = resolveEndpointFromSsm();
        if (fromSsm != null) return fromSsm;
        return "https://eks-d-xpress.codriverlabs.ai";
    }

    public String getRegion() {
        String env = System.getenv("AWS_REGION");
        if (env != null && !env.isBlank()) return env;
        return props.getProperty("region", "us-east-1");
    }

    public String getStreamUrl() {
        String env = System.getenv("EKS_DX_STREAM_URL");
        if (env != null && !env.isBlank()) return env;
        String fromFile = props.getProperty("stream-url");
        if (fromFile != null && !fromFile.isBlank()) return fromFile;
        return resolveParamFromSsm(SSM_PARAM_STREAM_URL);
    }

    public void save(String endpoint, String region) throws IOException {
        Files.createDirectories(CONFIG_DIR);
        Properties p = new Properties();
        if (endpoint != null) p.setProperty("endpoint", endpoint);
        if (region != null) p.setProperty("region", region);
        try (var out = Files.newOutputStream(CONFIG_FILE)) {
            p.store(out, "eks-dx CLI configuration");
        }
    }

    public static Path configFile() {
        return CONFIG_FILE;
    }

    private void load() {
        if (Files.exists(CONFIG_FILE)) {
            try (var in = Files.newInputStream(CONFIG_FILE)) {
                props.load(in);
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private String resolveEndpointFromSsm() {
        return resolveParamFromSsm(SSM_PARAM_ENDPOINT);
    }

    private String resolveParamFromSsm(String paramName) {
        try (SsmClient ssm = SsmClient.builder()
                .region(Region.of(getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build()) {
            return ssm.getParameter(GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(false)
                    .build())
                .parameter().value();
        } catch (Exception e) {
            return null;
        }
    }
}
