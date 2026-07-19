package ai.codriverlabs.ecp.karpenter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Ec2NodeClassSpec {

    private String instanceProfile;
    private String amiFamily;
    private String userData;
    private Boolean associatePublicIPAddress;
    private List<Map<String, Object>> amiSelectorTerms;
    private List<Map<String, Object>> subnetSelectorTerms;
    private List<Map<String, Object>> securityGroupSelectorTerms;
    private Map<String, String> tags;

    public String getInstanceProfile() { return instanceProfile; }
    public void setInstanceProfile(String v) { this.instanceProfile = v; }

    public String getAmiFamily() { return amiFamily; }
    public void setAmiFamily(String v) { this.amiFamily = v; }

    public String getUserData() { return userData; }
    public void setUserData(String v) { this.userData = v; }

    public Boolean getAssociatePublicIPAddress() { return associatePublicIPAddress; }
    public void setAssociatePublicIPAddress(Boolean v) { this.associatePublicIPAddress = v; }

    public List<Map<String, Object>> getAmiSelectorTerms() { return amiSelectorTerms; }
    public void setAmiSelectorTerms(List<Map<String, Object>> v) { this.amiSelectorTerms = v; }

    public List<Map<String, Object>> getSubnetSelectorTerms() { return subnetSelectorTerms; }
    public void setSubnetSelectorTerms(List<Map<String, Object>> v) { this.subnetSelectorTerms = v; }

    public List<Map<String, Object>> getSecurityGroupSelectorTerms() { return securityGroupSelectorTerms; }
    public void setSecurityGroupSelectorTerms(List<Map<String, Object>> v) { this.securityGroupSelectorTerms = v; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> v) { this.tags = v; }
}
