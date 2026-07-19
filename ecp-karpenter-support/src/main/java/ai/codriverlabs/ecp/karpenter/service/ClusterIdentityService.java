package ai.codriverlabs.ecp.karpenter.service;

import ai.codriverlabs.ecp.karpenter.model.ClusterIdentity;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves and caches Karpenter cluster bootstrap fields from in-cluster sources.
 *
 * Sources:
 * - apiServerEndpoint  — client.getMasterUrl() (in-cluster config, no API call)
 * - certificateAuthority — configmap/kube-root-ca.crt in kube-system (base64-encoded)
 * - serviceCidr — configmap/kubeadm-config ClusterConfiguration.serviceSubnet
 * - clusterName, tenantId, natGatewayEnabled — configmap/ecp-config
 *   (written by 18-install-ecp-karpenter-support.sh at cluster bootstrap)
 *
 * TTL: 5 minutes.
 */
@ApplicationScoped
public class ClusterIdentityService {

    private static final Logger LOG = Logger.getLogger(ClusterIdentityService.class);
    private static final long TTL_SECONDS = 300;

    @Inject KubernetesClient client;

    private final AtomicReference<ClusterIdentity> cache = new AtomicReference<>();
    private volatile Instant refreshAt = Instant.EPOCH;

    public ClusterIdentity get() {
        if (Instant.now().isAfter(refreshAt)) refresh();
        return cache.get();
    }

    public synchronized void refresh() {
        try {
            var identity = resolve();
            cache.set(identity);
            refreshAt = Instant.now().plusSeconds(TTL_SECONDS);
            LOG.infof("Resolved cluster identity: cluster=%s natGateway=%s serviceCidr=%s dnsIp=%s",
                identity.clusterName(), identity.natGatewayEnabled(),
                identity.serviceCidr(), identity.clusterDnsIp());
        } catch (Exception e) {
            LOG.errorf("Failed to resolve cluster identity: %s", e.getMessage());
            if (cache.get() == null) throw new IllegalStateException("Cluster identity unavailable", e);
        }
    }

    private ClusterIdentity resolve() throws Exception {
        ConfigMap eksDxConfig = client.configMaps().inNamespace("kube-system").withName("ecp-config").get();
        String serviceCidr = resolveServiceCidr(eksDxConfig);
        boolean natEnabled = "true".equalsIgnoreCase(get(eksDxConfig, "nat-gateway-enabled", "false"));
        // Use private subnet when NAT is available (nodes don't need public IPs)
        // Use public subnet when NAT is absent (nodes need direct internet access)
        String subnetId = natEnabled
            ? get(eksDxConfig, "private-subnet-id", null)
            : get(eksDxConfig, "public-subnet-id", null);
        return new ClusterIdentity(
            get(eksDxConfig, "cluster-name", "default"),
            get(eksDxConfig, "tenant-id", ""),
            resolveApiServerEndpoint(),
            resolveCertificateAuthority(),
            serviceCidr,
            computeClusterDnsIp(serviceCidr),
            natEnabled,
            subnetId,
            get(eksDxConfig, "security-group-id", null)
        );
    }

    private String resolveApiServerEndpoint() {
        var kubeadm = client.configMaps().inNamespace("kube-system").withName("kubeadm-config").get();
        if (kubeadm != null) {
            for (String line : kubeadm.getData().getOrDefault("ClusterConfiguration", "").split("\n")) {
                String t = line.trim();
                if (t.startsWith("controlPlaneEndpoint:")) {
                    String endpoint = t.substring("controlPlaneEndpoint:".length()).trim();
                    return endpoint.startsWith("https://") ? endpoint : "https://" + endpoint;
                }
            }
        }
        // Fallback to in-cluster client URL (works only inside the cluster)
        String url = client.getMasterUrl().toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String resolveCertificateAuthority() {
        var cm = client.configMaps().inNamespace("kube-system").withName("kube-root-ca.crt").get();
        if (cm == null) throw new IllegalStateException("configmap/kube-root-ca.crt not found");
        return Base64.getEncoder().encodeToString(cm.getData().get("ca.crt").getBytes());
    }

    private String resolveServiceCidr(ConfigMap eksDxConfig) {
        var kubeadm = client.configMaps().inNamespace("kube-system").withName("kubeadm-config").get();
        if (kubeadm != null) {
            for (String line : kubeadm.getData().getOrDefault("ClusterConfiguration", "").split("\n")) {
                String t = line.trim();
                if (t.startsWith("serviceSubnet:")) return t.substring("serviceSubnet:".length()).trim();
            }
        }
        String fallback = get(eksDxConfig, "serviceSubnet", null);
        if (fallback != null) return fallback;
        throw new IllegalStateException("serviceCidr not found in kubeadm-config or ecp-config");
    }

    private String get(ConfigMap cm, String key, String defaultValue) {
        if (cm != null && cm.getData() != null && cm.getData().containsKey(key))
            return cm.getData().get(key);
        if (defaultValue != null) LOG.warnf("ecp-config missing key '%s', using '%s'", key, defaultValue);
        return defaultValue;
    }

    /** Returns the 10th host address in the given CIDR (e.g. 10.96.0.0/12 → 10.96.0.10). */
    static String computeClusterDnsIp(String cidr) throws Exception {
        String[] parts = cidr.split("/");
        byte[] addr = InetAddress.getByName(parts[0]).getAddress();
        int prefix = Integer.parseInt(parts[1]);
        for (int i = prefix; i < 32; i++) addr[i / 8] &= (byte) ~(1 << (7 - (i % 8)));
        int carry = 10;
        for (int i = 3; i >= 0 && carry > 0; i--) {
            int sum = (addr[i] & 0xFF) + carry;
            addr[i] = (byte) (sum & 0xFF);
            carry = sum >> 8;
        }
        return InetAddress.getByAddress(addr).getHostAddress();
    }
}
