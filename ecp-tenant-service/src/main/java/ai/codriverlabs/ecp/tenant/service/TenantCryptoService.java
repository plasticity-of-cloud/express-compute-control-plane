package ai.codriverlabs.ecp.tenant.service;

import ai.codriverlabs.ecp.tenant.TenantNaming;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.SignRequest;
import software.amazon.awssdk.services.kms.model.SignResponse;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DeleteSecretRequest;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

/**
 * Generates and stores PKI material for managed tenants:
 * - Per-tenant CA (key pair + cert signed by shared KMS key)
 * - SA signing key pair (for kube-controller-manager token signing)
 * - JWKS derived from SA public key (for DynamoDB pre-registration)
 */
@ApplicationScoped
public class TenantCryptoService {

    private static final Logger LOG = Logger.getLogger(TenantCryptoService.class);

    @Inject KmsClient kms;
    @Inject SecretsManagerClient secretsManager;

    @ConfigProperty(name = "express-compute.crypto.kms-ca-key-id")
    String kmsKeyId;

    /**
     * Result of PKI generation — contains JWKS for DynamoDB and secret names for rollback.
     */
    public record CryptoResult(String jwks, String issuer, String caKeySecret, String caCrtSecret, String saKeySecret) {}

    /**
     * Generate CA + SA key material, sign CA cert with KMS, store in Secrets Manager.
     *
     * @param tenantId  tenant identifier
     * @return CryptoResult with JWKS JSON and SM secret names
     */
    public CryptoResult generateAndStore(String tenantId) {
        try {
            // 1. Generate CA key pair
            KeyPair caKeyPair = generateRsaKeyPair();
            LOG.infof("Generated CA key pair for tenant %s", tenantId);

            // 2. Create CA cert signed by KMS
            String caCertPem = createKmsSignedCaCert(tenantId, caKeyPair);
            LOG.infof("Created KMS-signed CA cert for tenant %s", tenantId);

            // 3. Generate SA signing key pair
            KeyPair saKeyPair = generateRsaKeyPair();
            LOG.infof("Generated SA signing key pair for tenant %s", tenantId);

            // 4. Derive JWKS from SA public key
            String jwks = deriveJwks((RSAPublicKey) saKeyPair.getPublic());

            // 5. Store in Secrets Manager
            String caKeySecretName = TenantNaming.secretPath(tenantId, "ca-key");
            String caCrtSecretName = TenantNaming.secretPath(tenantId, "ca-crt");
            String saKeySecretName = TenantNaming.secretPath(tenantId, "sa-key");

            secretsManager.createSecret(CreateSecretRequest.builder()
                .name(caKeySecretName).secretString(toPem(caKeyPair.getPrivate().getEncoded(), "PRIVATE KEY")).build());
            secretsManager.createSecret(CreateSecretRequest.builder()
                .name(caCrtSecretName).secretString(caCertPem).build());
            secretsManager.createSecret(CreateSecretRequest.builder()
                .name(saKeySecretName).secretString(toPem(saKeyPair.getPrivate().getEncoded(), "PRIVATE KEY")).build());

            LOG.infof("Stored PKI secrets for tenant %s", tenantId);

            String issuer = "https://ecp.codriverlabs.ai/clusters/" + tenantId;
            return new CryptoResult(jwks, issuer, caKeySecretName, caCrtSecretName, saKeySecretName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PKI for tenant " + tenantId, e);
        }
    }

    /**
     * Delete all PKI secrets for a tenant (used during rollback/deprovision).
     */
    public void deleteSecrets(String tenantId) {
        for (String suffix : new String[]{"ca-key", "ca-crt", "sa-key"}) {
            try {
                secretsManager.deleteSecret(DeleteSecretRequest.builder()
                    .secretId(TenantNaming.secretPath(tenantId, suffix))
                    .forceDeleteWithoutRecovery(true).build());
            } catch (Exception e) {
                LOG.warnf("Failed to delete secret %s/%s: %s", tenantId, suffix, e.getMessage());
            }
        }
    }

    // -- Internal helpers -----------------------------------------------------

    private KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    /**
     * Creates a self-signed CA certificate where the signature is produced by KMS.
     * The CA public key is the locally-generated key (so the instance can use ca.key to sign
     * kubelet certs), but the CA cert itself is signed by the shared KMS key to prove
     * control-plane issuance.
     */
    private String createKmsSignedCaCert(String tenantId, KeyPair caKeyPair) throws Exception {
        X500Name subject = new X500Name("CN=ecp-tenant-" + tenantId + ",O=express-compute");
        BigInteger serial = BigInteger.valueOf(Instant.now().toEpochMilli());
        Date notBefore = Date.from(Instant.now());
        Date notAfter = Date.from(Instant.now().plus(3650, ChronoUnit.DAYS)); // 10 years

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, caKeyPair.getPublic());
        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));

        // Sign with KMS using a custom ContentSigner
        ContentSigner kmsSigner = new KmsContentSigner();
        byte[] certBytes = certBuilder.build(kmsSigner).getEncoded();

        return toPem(certBytes, "CERTIFICATE");
    }

    /**
     * Derives a JWKS JSON string from an RSA public key.
     * Produces a minimal JWK Set with a single key (RS256, sig use).
     */
    String deriveJwks(RSAPublicKey publicKey) {
        String n = base64UrlEncode(publicKey.getModulus().toByteArray());
        String e = base64UrlEncode(publicKey.getPublicExponent().toByteArray());
        String kid = generateKid(publicKey);

        return """
            {"keys":[{"kty":"RSA","kid":"%s","use":"sig","alg":"RS256","n":"%s","e":"%s"}]}""".formatted(kid, n, e);
    }

    private String generateKid(RSAPublicKey publicKey) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(publicKey.getEncoded());
            return base64UrlEncode(digest).substring(0, 8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String base64UrlEncode(byte[] data) {
        // Strip leading zero byte from BigInteger two's complement representation
        if (data.length > 1 && data[0] == 0) {
            byte[] trimmed = new byte[data.length - 1];
            System.arraycopy(data, 1, trimmed, 0, trimmed.length);
            data = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String toPem(byte[] encoded, String type) {
        return "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
            + "\n-----END " + type + "-----\n";
    }

    /**
     * ContentSigner that delegates to AWS KMS for RSA-SHA256 signatures.
     */
    private class KmsContentSigner implements ContentSigner {
        private final ByteArrayOutputStream stream = new ByteArrayOutputStream();

        @Override
        public org.bouncycastle.asn1.x509.AlgorithmIdentifier getAlgorithmIdentifier() {
            return new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.sha256WithRSAEncryption);
        }

        @Override
        public OutputStream getOutputStream() {
            return stream;
        }

        @Override
        public byte[] getSignature() {
            byte[] tbsData = stream.toByteArray();
            SignResponse response = kms.sign(SignRequest.builder()
                .keyId(kmsKeyId)
                .message(SdkBytes.fromByteArray(tbsData))
                .messageType(MessageType.RAW)
                .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256)
                .build());
            return response.signature().asByteArray();
        }
    }
}
