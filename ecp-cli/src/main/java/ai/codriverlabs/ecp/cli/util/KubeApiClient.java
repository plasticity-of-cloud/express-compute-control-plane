package ai.codriverlabs.ecp.cli.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import javax.net.ssl.*;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Minimal kube-apiserver client that reads kubeconfig and fetches OIDC endpoints.
 * Supports static client cert/key, bearer token, and exec credential plugins (e.g. aws eks get-token).
 */
public class KubeApiClient {

    private final HttpClient http;
    private final String serverUrl;
    private String bearerToken;

    public KubeApiClient(String kubeconfigPathOrUrl) {
        // Allow passing a plain URL directly (e.g. for tests or --server flag)
        if (kubeconfigPathOrUrl != null && kubeconfigPathOrUrl.startsWith("http")) {
            this.serverUrl = kubeconfigPathOrUrl.replaceAll("/+$", "");
            this.http = HttpClient.newHttpClient();
        } else {
            KubeContext ctx = loadContext(kubeconfigPathOrUrl);
            this.serverUrl = ctx.server.replaceAll("/+$", "");
            this.http = HttpClient.newBuilder()
                    .sslContext(buildSslContext(ctx))
                    .build();
        }
    }

    public String get(String path) {
        // JWKS endpoint requires application/jwk-set+json; others accept application/json
        String accept = path.contains("jwks") ? "application/jwk-set+json" : "application/json";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + path))
                    .header("Accept", accept);
            if (bearerToken != null) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }
            var resp = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("kube-apiserver returned " + resp.statusCode() + ": " + resp.body());
            }
            return resp.body();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call kube-apiserver: " + e.getMessage(), e);
        }
    }

    // ---- kubeconfig parsing ----

    private record KubeContext(String server, String caCertPem, String clientCertPem,
                               String clientKeyPem, String token) {}

    private KubeContext loadContext(String kubeconfigPath) {
        try {
            Path path = kubeconfigPath != null
                    ? Path.of(kubeconfigPath)
                    : Path.of(System.getProperty("user.home"), ".kube", "config");

            ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
            JsonNode root = yaml.readTree(path.toFile());

            String currentContext = root.path("current-context").asText();
            JsonNode ctxEntry = findNamed(root.path("contexts"), currentContext).path("context");
            String clusterName = ctxEntry.path("cluster").asText();
            String userName = ctxEntry.path("user").asText();

            JsonNode cluster = findNamed(root.path("clusters"), clusterName).path("cluster");
            JsonNode user = findNamed(root.path("users"), userName).path("user");

            String server = cluster.path("server").asText();
            String ca = base64OrFile(cluster, "certificate-authority-data", "certificate-authority");
            String cert = base64OrFile(user, "client-certificate-data", "client-certificate");
            String key = base64OrFile(user, "client-key-data", "client-key");
            String token = user.path("token").asText(null);

            // exec credential plugin (e.g. aws eks get-token)
            JsonNode exec = user.path("exec");
            if (!exec.isMissingNode() && token == null) {
                token = execToken(exec);
            }

            this.bearerToken = token;
            return new KubeContext(server, ca, cert, key, token);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read kubeconfig: " + e.getMessage(), e);
        }
    }

    private JsonNode findNamed(JsonNode array, String name) {
        for (JsonNode item : array) {
            if (name.equals(item.path("name").asText())) return item;
        }
        throw new RuntimeException("Entry '" + name + "' not found in kubeconfig");
    }

    private String base64OrFile(JsonNode node, String dataField, String fileField) throws IOException {
        JsonNode data = node.path(dataField);
        if (!data.isMissingNode() && !data.asText().isBlank()) {
            return new String(Base64.getDecoder().decode(data.asText()));
        }
        JsonNode file = node.path(fileField);
        if (!file.isMissingNode() && !file.asText().isBlank()) {
            return new String(new FileInputStream(file.asText()).readAllBytes());
        }
        return null;
    }

    /** Runs the exec credential plugin and returns the bearer token. */
    private String execToken(JsonNode execNode) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(execNode.path("command").asText());
        for (JsonNode arg : execNode.path("args")) cmd.add(arg.asText());

        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(proc.getInputStream().readAllBytes());
        proc.waitFor();

        JsonNode result = new ObjectMapper().readTree(output);
        return result.path("status").path("token").asText(null);
    }

    // ---- SSL context ----

    private SSLContext buildSslContext(KubeContext ctx) {
        try {
            TrustManager[] trustManagers = ctx.caCertPem() != null
                    ? buildTrustManagers(ctx.caCertPem())
                    : trustAll();

            KeyManager[] keyManagers = (ctx.clientCertPem() != null && ctx.clientKeyPem() != null)
                    ? buildKeyManagers(ctx.clientCertPem(), ctx.clientKeyPem())
                    : null;

            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(keyManagers, trustManagers, null);
            return ssl;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build SSL context: " + e.getMessage(), e);
        }
    }

    private TrustManager[] buildTrustManagers(String caPem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate ca = (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(caPem.getBytes()));
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null);
        ks.setCertificateEntry("ca", ca);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return tmf.getTrustManagers();
    }

    private KeyManager[] buildKeyManagers(String certPem, String keyPem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(certPem.getBytes()));

        byte[] keyBytes = Base64.getDecoder().decode(
                keyPem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null);
        ks.setKeyEntry("client", privateKey, new char[0], new X509Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        return kmf.getKeyManagers();
    }

    @SuppressWarnings("all")
    private TrustManager[] trustAll() {
        return new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
        }};
    }
}
