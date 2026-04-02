package com.plcloud.eksauth.service;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class PodIdentityAssociationService {
    
    private static final Logger LOG = Logger.getLogger(PodIdentityAssociationService.class);
    
    @Inject
    KubernetesClient kubernetesClient;
    
    @ConfigProperty(name = "eks.pod-identity.configmap.name", defaultValue = "pod-identity-associations")
    String configMapName;
    
    @ConfigProperty(name = "eks.pod-identity.configmap.namespace", defaultValue = "kube-system")
    String configMapNamespace;
    
    public String getRoleArnForServiceAccount(String clusterName, String namespace, String serviceAccount) {
        try {
            ConfigMap configMap = kubernetesClient.configMaps()
                .inNamespace(configMapNamespace)
                .withName(configMapName)
                .get();
            
            if (configMap == null) {
                LOG.warnf("Pod identity associations ConfigMap not found: %s/%s", configMapNamespace, configMapName);
                return getDefaultRoleArn(namespace, serviceAccount);
            }
            
            Map<String, String> data = configMap.getData();
            if (data == null) {
                LOG.warn("Pod identity associations ConfigMap has no data");
                return getDefaultRoleArn(namespace, serviceAccount);
            }
            
            // Look for association key: cluster:namespace:serviceaccount
            String associationKey = String.format("%s:%s:%s", clusterName, namespace, serviceAccount);
            String roleArn = data.get(associationKey);
            
            if (roleArn != null) {
                LOG.infof("Found role association: %s -> %s", associationKey, roleArn);
                return roleArn;
            }
            
            // Fallback to namespace-level association
            String namespaceKey = String.format("%s:%s:*", clusterName, namespace);
            roleArn = data.get(namespaceKey);
            
            if (roleArn != null) {
                LOG.infof("Found namespace-level role association: %s -> %s", namespaceKey, roleArn);
                return roleArn;
            }
            
            LOG.warnf("No role association found for %s", associationKey);
            return getDefaultRoleArn(namespace, serviceAccount);
            
        } catch (Exception e) {
            LOG.errorf("Error looking up pod identity association: %s", e.getMessage());
            return getDefaultRoleArn(namespace, serviceAccount);
        }
    }
    
    private String getDefaultRoleArn(String namespace, String serviceAccount) {
        // For CI/CD use case, generate a predictable role ARN
        String accountId = Optional.ofNullable(System.getenv("AWS_ACCOUNT_ID")).orElse("123456789012");
        return String.format("arn:aws:iam::%s:role/eks-pod-identity-%s-%s", accountId, namespace, serviceAccount);
    }
    
    public String generateAssociationId(String clusterName, String namespace, String serviceAccount) {
        return String.format("assoc-%s-%s-%s-%d", clusterName, namespace, serviceAccount, System.currentTimeMillis());
    }
}
