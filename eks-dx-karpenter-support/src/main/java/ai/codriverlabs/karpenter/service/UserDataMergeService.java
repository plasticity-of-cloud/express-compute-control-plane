package ai.codriverlabs.karpenter.service;

import ai.codriverlabs.karpenter.model.ClusterIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merges cluster bootstrap fields into EC2NodeClass spec.userData.
 *
 * <p><b>BottleRocket</b>: injects managed keys into the {@code [settings.kubernetes]} TOML section.
 * <p><b>AL2023 / Custom</b>: prepends an {@code application/node.eks.aws} MIME part.
 *
 * <p>Both paths are idempotent — if the managed marker is already present the method returns
 * {@code null}, signalling the reconciler/webhook to skip the patch.
 */
@ApplicationScoped
public class UserDataMergeService {

    private static final Logger LOG = Logger.getLogger(UserDataMergeService.class);
    /** Written into every managed userData block so idempotency checks are reliable. */
    static final String MANAGED_MARKER = "# eks-dx-managed";
    private static final String MIME_BOUNDARY = "//";

    /**
     * Merges cluster identity into userData.
     *
     * @return merged userData string, or {@code null} if already up-to-date (caller must skip patch)
     */
    public String merge(String amiFamily, String existingUserData, ClusterIdentity identity) {
        return isBottleRocket(amiFamily)
            ? mergeBottleRocket(existingUserData, identity)
            : mergeAl2(existingUserData, identity);
    }

    // ── BottleRocket ──────────────────────────────────────────────────────────

    private String mergeBottleRocket(String existing, ClusterIdentity id) {
        if (existing != null && existing.contains(MANAGED_MARKER)) {
            LOG.debugf("BottleRocket userData already has managed fields — idempotent no-op");
            return null;
        }
        if (existing == null || existing.isBlank()) return tomlBlock(id);

        Matcher m = Pattern.compile("(?m)^\\[settings\\.kubernetes\\]\\s*$").matcher(existing);
        if (m.find()) {
            // Inject managed keys right after the existing section header
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

    private String mergeAl2(String existing, ClusterIdentity id) {
        if (existing != null && existing.contains("application/node.eks.aws") && existing.contains(MANAGED_MARKER)) {
            LOG.debugf("AL2023 userData already has managed fields — idempotent no-op");
            return null;
        }
        String nodeEksAwsPart = nodeEksAwsPart(id);
        if (existing == null || existing.isBlank()) return buildMime(nodeEksAwsPart, null);

        if (existing.startsWith("MIME-Version:")) {
            // Prepend the node.eks.aws part before existing MIME parts
            int firstPart = existing.indexOf("--" + MIME_BOUNDARY);
            if (firstPart == -1) return buildMime(nodeEksAwsPart, existing);
            return existing.substring(0, firstPart)
                + "--" + MIME_BOUNDARY + "\n" + nodeEksAwsPart + "\n"
                + existing.substring(firstPart);
        }
        // Wrap non-MIME existing content as a shell-script part
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

    private boolean isBottleRocket(String amiFamily) {
        return amiFamily != null && amiFamily.equalsIgnoreCase("Bottlerocket");
    }
}
