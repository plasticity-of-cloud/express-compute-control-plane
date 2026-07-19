package ai.codriverlabs.ecp.cli.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration test against the real kubeconfig context.
 * Run with: mvn test -pl ecp-cli -Dkube.live=true
 */
@EnabledIfSystemProperty(named = "kube.live", matches = "true")
class KubeApiClientLiveTest {

    @Test
    void fetchJwks_fromCurrentContext() {
        KubeApiClient client = new KubeApiClient(null); // null = ~/.kube/config
        String jwks = client.get("/openid/v1/jwks");

        assertNotNull(jwks);
        assertTrue(jwks.contains("\"keys\""), "Expected JWKS response, got: " + jwks);
        System.out.println("JWKS response:\n" + jwks);
    }

    @Test
    void fetchOidcDiscovery_fromCurrentContext() {
        KubeApiClient client = new KubeApiClient(null);
        String oidc = client.get("/.well-known/openid-configuration");

        assertNotNull(oidc);
        assertTrue(oidc.contains("\"issuer\""), "Expected OIDC config, got: " + oidc);
        System.out.println("OIDC discovery:\n" + oidc);
    }
}
