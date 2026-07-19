package ai.codriverlabs.ecp.karpenter.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Reads NodePool resources from the cluster to extract CPU architecture requirements
 * for a given EC2NodeClass name.
 *
 * Used by the webhook to resolve only the relevant AMI architectures.
 */
@ApplicationScoped
public class NodePoolArchService {

    private static final Logger LOG = Logger.getLogger(NodePoolArchService.class);
    private static final List<String> ALL_ARCHES = List.of("arm64", "x86_64");

    @Inject KubernetesClient client;
    @Inject ObjectMapper mapper;

    /**
     * Returns the list of CPU architectures required by NodePools that reference the given EC2NodeClass.
     * Falls back to both arches if no NodePool is found or arch requirement is unspecified.
     */
    public List<String> archesFor(String ec2NodeClassName) {
        try {
            var nodePools = client.genericKubernetesResources("karpenter.sh/v1", "NodePool")
                .list().getItems();

            for (var np : nodePools) {
                var spec = np.getAdditionalProperties().get("spec");
                if (!(spec instanceof Map<?, ?> specMap)) continue;

                // Check nodeClassRef.name matches
                var template = specMap.get("template");
                if (!(template instanceof Map<?, ?> tmpl)) continue;
                var npSpec = tmpl.get("spec");
                if (!(npSpec instanceof Map<?, ?> npSpecMap)) continue;
                var ref = npSpecMap.get("nodeClassRef");
                if (!(ref instanceof Map<?, ?> refMap)) continue;
                if (!ec2NodeClassName.equals(refMap.get("name"))) continue;

                // Extract kubernetes.io/arch requirement values
                var requirements = npSpecMap.get("requirements");
                if (!(requirements instanceof List<?> reqList)) continue;

                for (var req : reqList) {
                    if (!(req instanceof Map<?, ?> reqMap)) continue;
                    if (!"kubernetes.io/arch".equals(reqMap.get("key"))) continue;
                    var values = reqMap.get("values");
                    if (!(values instanceof List<?> archValues)) continue;

                    List<String> arches = archValues.stream()
                        .map(Object::toString)
                        // NodePool uses "amd64", SSM uses "x86_64"
                        .map(a -> "amd64".equals(a) ? "x86_64" : a)
                        .filter(ALL_ARCHES::contains)
                        .distinct()
                        .toList();

                    if (!arches.isEmpty()) {
                        LOG.infof("NodePool arch for EC2NodeClass/%s: %s", ec2NodeClassName, arches);
                        return arches;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("Could not read NodePools for EC2NodeClass/%s: %s — using all arches", ec2NodeClassName, e.getMessage());
        }

        LOG.infof("No arch requirement found for EC2NodeClass/%s — resolving all arches", ec2NodeClassName);
        return ALL_ARCHES;
    }
}
