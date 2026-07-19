package ai.codriverlabs.ecp.tenant.service;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;

import static org.junit.jupiter.api.Assertions.*;

class TenantCryptoServiceTest {

    private final TenantCryptoService service = new TenantCryptoService();

    @Test
    void deriveJwks_producesValidJwkSet() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();

        String jwks = service.deriveJwks(pub);

        assertTrue(jwks.contains("\"kty\":\"RSA\""));
        assertTrue(jwks.contains("\"use\":\"sig\""));
        assertTrue(jwks.contains("\"alg\":\"RS256\""));
        assertTrue(jwks.contains("\"n\":\""));
        assertTrue(jwks.contains("\"e\":\""));
        assertTrue(jwks.contains("\"kid\":\""));
        // Must be valid JSON structure
        assertTrue(jwks.startsWith("{\"keys\":["));
        assertTrue(jwks.endsWith("]}"));
    }

    @Test
    void deriveJwks_differentKeys_produceDifferentKids() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);

        String jwks1 = service.deriveJwks((RSAPublicKey) gen.generateKeyPair().getPublic());
        String jwks2 = service.deriveJwks((RSAPublicKey) gen.generateKeyPair().getPublic());

        assertNotEquals(jwks1, jwks2);
    }
}
