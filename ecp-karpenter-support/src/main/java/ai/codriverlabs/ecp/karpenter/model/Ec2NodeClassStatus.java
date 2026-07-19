package ai.codriverlabs.ecp.karpenter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Ec2NodeClassStatus {

    private List<Map<String, Object>> conditions = new ArrayList<>();

    public List<Map<String, Object>> getConditions() { return conditions; }
    public void setConditions(List<Map<String, Object>> v) { this.conditions = v; }
}
