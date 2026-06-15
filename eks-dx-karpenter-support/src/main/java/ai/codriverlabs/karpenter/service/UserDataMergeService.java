package ai.codriverlabs.karpenter.service;

import ai.codriverlabs.karpenter.model.ClusterIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merges cluster bootstrap fields into EC2NodeClass spec.userData.
 *
 * <p><b>IMPORTANT — amiFamily must always be {@code Custom}</b>: Karpenter ≥1.10 calls
 * {@code eks:DescribeCluster} / {@code ResolveClusterCIDR} for any non-Custom amiFamily,
 * even when {@code eksControlPlane=false}. The actual node OS is indicated by the annotation
 * {@code eks-dx.codriverlabs.ai/node-variant}, not by {@code spec.amiFamily}.
 *
 * <p><b>bottlerocket*</b>: injects managed keys into {@code [settings.kubernetes]} TOML.
 * <p><b>al2023* / everything else</b>: prepends an {@code application/node.eks.aws} MIME part.
 *
 * <p>Both paths are idempotent — returns {@code null} if already managed.
 */
@ApplicationScoped
public class UserDataMergeService {

    private static final Logger LOG = Logger.getLogger(UserDataMergeService.class);
    static final String MANAGED_MARKER = "# eks-dx-managed";
    /** Annotation on EC2NodeClass that controls userData format — NOT spec.amiFamily. */
    public static final String NODE_VARIANT_ANNOTATION = "eks-dx.codriverlabs.ai/node-variant";
    private static final String MIME_BOUNDARY = "//";

    /**
     * @param nodeVariant value of annotation {@value NODE_VARIANT_ANNOTATION}
     *                    (e.g. "bottlerocket", "al2023", "al2023-gpu") — NOT spec.amiFamily
     * @return merged userData, or {@code null} if already up-to-date (caller must skip patch)
     */
    public String merge(String nodeVariant, String existingUserData, ClusterIdentity identity) {
        return isBottleRocket(nodeVariant)
            ? mergeBottleRocket(existingUserData, identity)
            : mergeAl2023(existingUserData, identity);
    }

    // ── Bottlerocket ──────────────────────────────────────────────────────────

    private String mergeBottleRocket(String existing, ClusterIdentity id) {
        if (existing != null && existing.contains(MANAGED_MARKER)) {
            LOG.debugf("Bottlerocket userData already has managed fields — idempotent no-op");
            return null;
        }
        if (existing == null || existing.isBlank()) return tomlBlock(id);

        Matcher m = Pattern.compile("(?m)^\\[settings\\.kubernetes\\]\\s*$").matcher(existing);
        if (m.find()) {
            return existing.substring(0, m.end()) + "\n" + tomlKeys(id) + "\n" + existing.substring(m.end());
        }
        return existing + "\n" + tomlBlock(id);
    }

    private String tomlBlock(ClusterIdentity id) {
        return MANAGED_MARKER + "\n[settings.kubernetes]\n" + tomlKeys(id);
    }

    private String tomlKeys(ClusterIdentity id) {
        return "api-server = \"" + id.apiServerEndpoint() + "\"\n"
            + "cluster-certificate = \"" + id.certificateAuthority() + "\"\n"
            + "cluster-name = \"" + id.clusterName() + "\"\n"
            + "cluster-dns-ip = \"" + id.clusterDnsIp() + "\"";
    }

    // ── AL2023 ────────────────────────────────────────────────────────────────

    private String mergeAl2023(String existing, ClusterIdentity id) {
        if (existing != null && existing.contains("application/node.eks.aws") && existing.contains(MANAGED_MARKER)) {
            LOG.debugf("AL2023 userData already has managed fields — idempotent no-op");
            return null;
        }
        String nodeEksAwsPart = nodeEksAwsPart(id);
        if (existing == null || existing.isBlank()) return buildMime(nodeEksAwsPart, null);

        if (existing.startsWith("MIME-Version:")) {
            int firstPart = existing.indexOf("--" + MIME_BOUNDARY);
            if (firstPart == -1) return buildMime(nodeEksAwsPart, existing);
            return existing.substring(0, firstPart)
                + "--" + MIME_BOUNDARY + "\n" + nodeEksAwsPart + "\n"
                + existing.substring(firstPart);
        }
        return buildMime(nodeEksAwsPart, existing);
    }

    private String nodeEksAwsPart(ClusterIdentity id) {
        return "Content-Type: application/node.eks.aws\n\n"
            + MANAGED_MARKER + "\n"
            + "apiVersion: node.eks.aws/v1alpha1\n"
            + "kind: NodeConfig\n"
            + "spec:\n"
            + "  cluster:\n"
            + "    name: " + id.clusterName() + "\n"
            + "    apiServerEndpoint: " + id.apiServerEndpoint() + "\n"
            + "    certificateAuthority: " + id.certificateAuthority() + "\n"
            + "    cidr: " + id.serviceCidr() + "\n";
    }

    private String buildMime(String nodeEksAwsPart, String existingContent) {
        var sb = new StringBuilder();
        sb.append("MIME-Version: 1.0\n");
        sb.append("Content-Type: multipart/mixed; boundary=\"").append(MIME_BOUNDARY).append("\"\n\n");
        sb.append("--").append(MIME_BOUNDARY).append("\n").append(nodeEksAwsPart);
        if (existingContent != null && !existingContent.isBlank()) {
            sb.append("\n--").append(MIME_BOUNDARY).append("\n");
            sb.append("Content-Type: text/x-shellscript; charset=\"us-ascii\"\n\n");
            sb.append(existingContent).append("\n");
        }
        sb.append("\n--").append(MIME_BOUNDARY).append("--\n");
        return sb.toString();
    }

    private boolean isBottleRocket(String nodeVariant) {
        return nodeVariant != null && nodeVariant.toLowerCase().startsWith("bottlerocket");
    }
}
