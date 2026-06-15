package ai.codriverlabs.karpenter.service;

import ai.codriverlabs.karpenter.model.ClusterIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserDataMergeServiceTest {

    UserDataMergeService service;

    static final ClusterIdentity ID = new ClusterIdentity(
        "my-cluster", "https://10.0.0.1:6443", "base64ca==", "10.96.0.0/12", "10.96.0.10"
    );

    @BeforeEach
    void setUp() {
        service = new UserDataMergeService();
    }

    // ── Bottlerocket ──────────────────────────────────────────────────────────

    @Test
    void br_empty_injectsBlock() {
        String r = service.merge("bottlerocket", null, ID);
        assertNotNull(r);
        assertTrue(r.contains("[settings.kubernetes]"));
        assertTrue(r.contains("api-server = \"https://10.0.0.1:6443\""));
        assertTrue(r.contains("cluster-dns-ip = \"10.96.0.10\""));
        assertTrue(r.contains("cluster-name = \"my-cluster\""));
        assertTrue(r.contains("cluster-certificate = \"base64ca==\""));
    }

    @Test
    void br_gpu_variant_injectsBlock() {
        String r = service.merge("bottlerocket-gpu", null, ID);
        assertNotNull(r);
        assertTrue(r.contains("[settings.kubernetes]"));
    }

    @Test
    void br_idempotent_returnsNull() {
        String first = service.merge("bottlerocket", null, ID);
        assertNull(service.merge("bottlerocket", first, ID));
    }

    @Test
    void br_existingContentPreserved() {
        String r = service.merge("bottlerocket", "[settings.network]\nhostname = \"node1\"", ID);
        assertNotNull(r);
        assertTrue(r.contains("[settings.network]"));
        assertTrue(r.contains("[settings.kubernetes]"));
    }

    @Test
    void br_existingSectionMerged() {
        String r = service.merge("bottlerocket", "[settings.kubernetes]\nsome-key = \"val\"", ID);
        assertNotNull(r);
        assertTrue(r.contains("api-server"));
        assertTrue(r.contains("some-key"));
    }

    // ── AL2023 ────────────────────────────────────────────────────────────────

    @Test
    void al2023_empty_createsMime() {
        String r = service.merge("al2023", null, ID);
        assertNotNull(r);
        assertTrue(r.startsWith("MIME-Version:"));
        assertTrue(r.contains("application/node.eks.aws"));
        assertTrue(r.contains("apiServerEndpoint: https://10.0.0.1:6443"));
        assertTrue(r.contains("cidr: 10.96.0.0/12"));
    }

    @Test
    void al2023_gpu_variant() {
        String r = service.merge("al2023-gpu", null, ID);
        assertNotNull(r);
        assertTrue(r.contains("application/node.eks.aws"));
    }

    @Test
    void al2023_idempotent_returnsNull() {
        String first = service.merge("al2023", null, ID);
        assertNull(service.merge("al2023", first, ID));
    }

    @Test
    void al2023_existingShellScriptWrapped() {
        String r = service.merge("al2023", "#!/bin/bash\necho hello", ID);
        assertNotNull(r);
        assertTrue(r.contains("application/node.eks.aws"));
        assertTrue(r.contains("#!/bin/bash"));
    }

    @Test
    void al2023_existingMimePrepended() {
        String existing = "MIME-Version: 1.0\nContent-Type: multipart/mixed; boundary=\"//\"\n\n--//\nContent-Type: text/x-shellscript\n\n#!/bin/bash\n\n--//--\n";
        String r = service.merge("al2023", existing, ID);
        assertNotNull(r);
        assertTrue(r.contains("application/node.eks.aws"));
        assertTrue(r.contains("#!/bin/bash"));
    }

    @Test
    void nullVariant_treatedAsAl2023() {
        // null node-variant annotation → default to AL2023 MIME format
        String r = service.merge(null, null, ID);
        assertNotNull(r);
        assertTrue(r.contains("application/node.eks.aws"));
    }

    // ── ClusterIdentityService.computeClusterDnsIp ───────────────────────────

    @Test
    void dnsIp_standard() throws Exception {
        assertEquals("10.96.0.10", ClusterIdentityService.computeClusterDnsIp("10.96.0.0/12"));
    }

    @Test
    void dnsIp_slash16() throws Exception {
        assertEquals("172.20.0.10", ClusterIdentityService.computeClusterDnsIp("172.20.0.0/16"));
    }

    @Test
    void dnsIp_slash24() throws Exception {
        assertEquals("192.168.1.10", ClusterIdentityService.computeClusterDnsIp("192.168.1.0/24"));
    }
}
