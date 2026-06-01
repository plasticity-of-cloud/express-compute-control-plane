package ai.codriverlabs.eksdx.cli.config;

import ai.codriverlabs.eksdx.cli.util.AwsSigV4Signer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Reads/writes ~/.eks-d-xpress/config.
 * Endpoint resolution order: EKS_DX_ENDPOINT env → config file → SSM → default.
 */
public class EksDxConfig {

    private static final Path CONFIG_DIR = Path.of(
            System.getenv("HOME") != null ? System.getenv("HOME") : System.getProperty("user.home"),
            ".eks-d-xpress");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config");
    static final String SSM_PARAM_ENDPOINT = "/eks-d-xpress/control-plane/api/endpoint";
    static final String SSM_PARAM_STREAM_URL = "/eks-d-xpress/control-plane/api/stream-url";
    static final String SSM_PARAM_PROVISIONING_URL = "/eks-d-xpress/control-plane/api/provisioning-url";

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
        if (fromSsm != null) { cacheProperty("endpoint", fromSsm); return fromSsm; }
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
        String fromSsm = resolveParamFromSsm(SSM_PARAM_STREAM_URL);
        if (fromSsm != null) { cacheProperty("stream-url", fromSsm); return fromSsm; }
        return null;
    }

    public String getProvisioningUrl() {
        String env = System.getenv("EKS_DX_PROVISIONING_URL");
        if (env != null && !env.isBlank()) return env;
        String fromFile = props.getProperty("provisioning-url");
        if (fromFile != null && !fromFile.isBlank()) return fromFile;
        String fromSsm = resolveParamFromSsm(SSM_PARAM_PROVISIONING_URL);
        if (fromSsm != null) { cacheProperty("provisioning-url", fromSsm); return fromSsm; }
        return null;
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

    private void cacheProperty(String key, String value) {
        props.setProperty(key, value);
        try {
            Files.createDirectories(CONFIG_DIR);
            try (var out = Files.newOutputStream(CONFIG_FILE)) {
                props.store(out, "eks-dx CLI configuration");
            }
        } catch (IOException ignored) {}
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
        try {
            String region = getRegion();
            AwsSigV4Signer signer = AwsSigV4Signer.create(region);
            if (signer == null) return null;

            String body = "{\"Name\":\"" + paramName + "\",\"WithDecryption\":false}";
            URI uri = URI.create("https://ssm." + region + ".amazonaws.com/");

            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri);
            signer.sign(builder, "POST", uri, body, "ssm", "application/x-amz-json-1.1",
                    java.util.Map.of("X-Amz-Target", "AmazonSSM.GetParameter"));
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            JsonNode root = new ObjectMapper().readTree(response.body());
            return root.path("Parameter").path("Value").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
